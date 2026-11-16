package dev.g5.companion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.coroutines.resume

const val IMAGE_VIDEO_DIR = "DCIM/DPVR_G5"
// MediaStore only accepts audio under audio directories (DCIM is images/video only).
const val AUDIO_DIR = "Recordings/DPVR_G5"

/**
 * Media offload, as the DPVR app does it for "S"-series (P2P) glasses:
 * BLE cmd 57 [0x31] -> glasses start Wi-Fi Direct and reply cmd 37 with their SSID -> we join the peer whose
 * name contains it (PBC) -> Novatek-style HTTP server: `?custom=1&cmd=3015` lists files (XML with NAME/SIZE),
 * `DCIM/100HUNTI/<name>` serves each, `?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<name>` deletes one ->
 * BLE cmd 68 ends the session.
 */
object MediaSync {
    private const val TAG = "MediaSync"
    // ponytail: sequential like the vendor app; the glasses' HTTP server is single-threaded. Raise if it copes.
    private const val PARALLEL_DOWNLOADS = 1
    private const val CONNECT_TIMEOUT_MS = 90_000L
    private const val FALLBACK_HOST = "192.168.49.207" // hardcoded in the vendor app for P2P mode
    private const val MEDIA_DIR = "DCIM/100HUNTI/"

    data class Remote(val name: String, val size: Long)

    /** Parses the glasses' file list XML (`<File><NAME>…</NAME><SIZE>…</SIZE>…</File>`). */
    fun parseFileList(xml: String): List<Remote> =
        Regex("<File>(.*?)</File>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            fun tag(t: String) = Regex("<$t>\\s*(.*?)\\s*</$t>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1)
            val name = tag("NAME")?.substringAfterLast('\\')?.substringAfterLast('/') ?: return@mapNotNull null
            Remote(name, tag("SIZE")?.toLongOrNull() ?: -1)
        }.filter { isSafeName(it.name) }.distinctBy { it.name }.toList()

    data class Progress(
        val running: Boolean = false,
        val stage: String = "",
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val bytes: Long = 0,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress = _progress.asStateFlow()

    fun start(context: Context) {
        if (_progress.value.running) return
        val app = context.applicationContext
        Glasses.scope.launch {
            _progress.value = Progress(running = true, stage = "Starting")
            val result = runCatching { sync(app) }
            result.exceptionOrNull()?.let { Log.e(TAG, "sync failed", it) }
            _progress.update { it.copy(running = false, stage = if (result.isSuccess) "Done" else "Failed", error = result.exceptionOrNull()?.message) }
            Glasses.refreshMediaCount()
        }
    }

    private fun stage(s: String) { Log.i(TAG, s); _progress.update { it.copy(stage = s) } }

    @SuppressLint("MissingPermission")
    private suspend fun sync(ctx: Context) {
        check(Glasses.state.value.connected) { "Glasses are not connected" }
        val p2p = ctx.getSystemService(WifiP2pManager::class.java)
        val channel = p2p.initialize(ctx, Looper.getMainLooper(), null)
        val groupOwner = CompletableDeferred<String?>()
        var ssid: String? = null
        var connectSent = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> p2p.requestPeers(channel) { list ->
                        val want = ssid ?: return@requestPeers
                        val peer = list.deviceList.firstOrNull { it.deviceName?.contains(want) == true }
                        if (peer != null && !connectSent) {
                            connectSent = true
                            stage("Connecting to ${peer.deviceName}")
                            val cfg = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress; wps.setup = WpsInfo.PBC }
                            p2p.connect(channel, cfg, listener("connect") { connectSent = false })
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> p2p.requestConnectionInfo(channel) { info ->
                        if (info?.groupFormed == true) groupOwner.complete(info.groupOwnerAddress?.hostAddress.takeUnless { info.isGroupOwner })
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        var allDone = false
        try {
            stage("Waking glasses Wi-Fi")
            await { p2p.removeGroup(channel, it) }
            ssid = requestSsid() ?: error("Glasses did not start Wi-Fi")
            Log.i(TAG, "glasses SSID $ssid")
            stage("Waiting for glasses on Wi-Fi Direct")
            p2p.discoverPeers(channel, listener("discover"))
            val owner = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { groupOwner.await() }
                ?: error("Could not join the glasses' Wi-Fi Direct (is phone Wi-Fi on and hotspot off?)")
            allDone = download(ctx, listOfNotNull(owner, FALLBACK_HOST).distinct())
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
            // [0x30, 0] = finished and clear the glasses' "new media" counter; [0x30, 1] = finished, keep it.
            Glasses.send(Eyevue.frame(Eyevue.FILE_DOWNLOAD_FINISH, 0x30, if (allDone) 0 else 1))
            p2p.stopPeerDiscovery(channel, null)
            await { p2p.removeGroup(channel, it) }
            channel.close()
        }
    }

    /** BLE cmd 57 [0x31 = P2P]; the glasses answer cmd 37 with their Wi-Fi Direct name. */
    private suspend fun requestSsid(): String? = coroutineScope {
        repeat(3) {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                Glasses.frames.first { it.cmd == Eyevue.WIFI_SSID && it.payload.size >= 5 }
            }
            Glasses.send(Eyevue.frame(Eyevue.GET_WIFI_INFO, 0x31))
            withTimeoutOrNull(15_000) { reply.await() }?.let { f ->
                return@coroutineScope String(f.payload, Charsets.UTF_8).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
            }
            reply.cancel()
        }
        null
    }

    /** Returns true when every file on the glasses is safely on the phone. */
    private suspend fun download(ctx: Context, hosts: List<String>): Boolean {
        val http = httpClient(ctx)
        stage("Reading file list")
        val (host, files) = fetchIndex(http, hosts)
        val base = "http://$host/"
        val prefs = ctx.getSharedPreferences("synced", Context.MODE_PRIVATE)
        val autoDelete = Prefs(ctx).autoDelete
        val todo = files.filterNot { prefs.getBoolean(it.name, false) && existsLocally(ctx, it.name) }
        _progress.update { it.copy(total = todo.size, stage = if (todo.isEmpty()) "Already up to date" else "Downloading") }

        val gate = Semaphore(PARALLEL_DOWNLOADS)
        val failures = AtomicInteger()
        coroutineScope {
            todo.map { f ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val ok = runCatching { downloadOne(ctx, http, base + MEDIA_DIR, f) }
                            .onFailure { Log.w(TAG, "download ${f.name} failed", it) }.isSuccess
                        if (ok) prefs.edit().putBoolean(f.name, true).apply() else failures.incrementAndGet()
                        _progress.update { it.copy(done = it.done + if (ok) 1 else 0, failed = failures.get()) }
                    }
                }
            }.awaitAll()
        }

        if (autoDelete) {
            // Only files whose phone copy is confirmed; anything that failed stays on the glasses.
            val verified = files.filter { prefs.getBoolean(it.name, false) && existsLocally(ctx, it.name) }
            if (verified.isNotEmpty()) stage("Deleting ${verified.size} verified file(s) from glasses")
            withContext(Dispatchers.IO) {
                verified.forEach { f ->
                    val url = base + "?custom=1&cmd=4003&str=A:\\DCIM\\100HUNTI\\" + f.name
                    runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { check(it.isSuccessful) { "HTTP ${it.code}" } } }
                        .onSuccess { prefs.edit().remove(f.name).apply() }
                        .onFailure { Log.w(TAG, "delete ${f.name} failed", it) }
                }
            }
        }
        if (failures.get() > 0) error("${failures.get()} file(s) failed; tap Sync again to retry")
        return true
    }

    private suspend fun fetchIndex(http: OkHttpClient, hosts: List<String>): Pair<String, List<Remote>> = withContext(Dispatchers.IO) {
        // The HTTP server can take a few seconds to come up after the P2P group forms.
        repeat(10) { attempt ->
            for (host in hosts) {
                val body = runCatching {
                    http.newCall(Request.Builder().url("http://$host/?custom=1&cmd=3015").build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body?.string().orEmpty()
                    }
                }.onFailure { Log.d(TAG, "index from $host: ${it.message}") }.getOrNull()
                if (body != null && ("<File" in body || "LIST" in body)) {
                    Log.i(TAG, "index from $host: " + Regex("<NAME>(.*?)</NAME>").findAll(body).joinToString { it.groupValues[1] })
                    return@withContext host to parseFileList(body)
                }
            }
            delay(1_000L + attempt * 500L)
        }
        error("Glasses media server did not respond")
    }

    fun isSafeName(n: String) = n.isNotEmpty() && '/' !in n && '\\' !in n && !n.startsWith(".") && kindOf(n) != null

    private enum class Kind(val dir: String) {
        IMAGE(IMAGE_VIDEO_DIR), VIDEO(IMAGE_VIDEO_DIR), AUDIO(AUDIO_DIR);

        val collection: Uri
            get() = when (this) {
                IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
    }

    private fun kindOf(name: String) = when (name.substringAfterLast('.').lowercase(Locale.US)) {
        "jpg", "jpeg" -> Kind.IMAGE
        "mp4" -> Kind.VIDEO
        "opus", "wav", "mp3" -> Kind.AUDIO
        else -> null
    }

    private fun mimeOf(name: String) = when (kindOf(name)) {
        Kind.IMAGE -> "image/jpeg"
        Kind.VIDEO -> "video/mp4"
        Kind.AUDIO -> when (name.substringAfterLast('.').lowercase(Locale.US)) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "audio/ogg"
        }
        null -> "application/octet-stream"
    }

    private fun downloadOne(ctx: Context, http: OkHttpClient, base: String, remote: Remote) {
        val name = remote.name
        val kind = requireNotNull(kindOf(name))
        val tmp = File(ctx.cacheDir, "dl_$name")
        try {
            http.newCall(Request.Builder().url(base + name).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("empty body")
                val expected = remote.size.takeIf { it > 0 } ?: body.contentLength()
                val written = body.byteStream().use { input -> tmp.outputStream().use { out -> copyCounting(input, out) } }
                if (written == 0L || (expected >= 0 && written != expected)) throw IOException("size mismatch $written/$expected")
            }
            if (kind == Kind.IMAGE) verifyJpeg(tmp)
            val payload = if (name.endsWith(".opus", ignoreCase = true)) {
                // Fall back to the raw bytes if the layout is unrecognized; nothing is lost either way.
                OggOpus.wrap(tmp.readBytes())?.also { tmp.writeBytes(it) }
                tmp
            } else tmp
            saveToMediaStore(ctx, kind, name, payload)
        } finally {
            tmp.delete()
        }
    }

    private fun copyCounting(input: java.io.InputStream, out: java.io.OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            out.write(buf, 0, n)
            total += n
            _progress.update { it.copy(bytes = it.bytes + n) }
        }
    }

    private fun verifyJpeg(f: File) {
        val head = f.inputStream().use { it.readNBytes(2) }
        if (head.size < 2 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) throw IOException("not a JPEG")
    }

    private fun saveToMediaStore(ctx: Context, kind: Kind, name: String, file: File) {
        val cr = ctx.contentResolver
        existingUri(ctx, kind, name)?.let { cr.delete(it, null, null) } // replace a partial/stale copy
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(kind.collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("open failed")
            val stored = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            if (stored != file.length()) throw IOException("stored size $stored != ${file.length()}")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
    }

    private fun existingUri(ctx: Context, kind: Kind, name: String): Uri? =
        ctx.contentResolver.query(
            kind.collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "${kind.dir}%"), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(kind.collection, c.getLong(0)) else null }

    private fun existsLocally(ctx: Context, name: String) = kindOf(name)?.let { existingUri(ctx, it, name) } != null

    // ---------- Wi-Fi Direct helpers ----------

    /**
     * Route HTTP over the P2P link, not cellular/default Wi-Fi. Prefer the framework Network for the p2p
     * interface; if the platform does not register one, bind each socket to our p2p interface address.
     */
    @Suppress("DEPRECATION")
    private fun httpClient(ctx: Context): OkHttpClient {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getLinkProperties(n)?.interfaceName?.let { it.startsWith("p2p") || it.contains("wfd") } == true
        }
        val factory = network?.socketFactory ?: p2pLocalAddress()?.let(::BoundSocketFactory)
        Log.i(TAG, "HTTP routing: ${if (network != null) "p2p Network" else if (factory != null) "bound socket" else "default"}")
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply { factory?.let { socketFactory(it) } }
            .build()
    }

    private fun p2pLocalAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.filter { it.isUp && it.name.startsWith("p2p") }
        ?.flatMap { it.inetAddresses.toList() }
        ?.firstOrNull { it is Inet4Address }

    private class BoundSocketFactory(private val local: InetAddress) : SocketFactory() {
        override fun createSocket() = Socket().apply { bind(java.net.InetSocketAddress(local, 0)) }
        override fun createSocket(host: String, port: Int) = Socket(host, port, local, 0)
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int) = Socket(host, port, local, 0)
        override fun createSocket(host: InetAddress, port: Int) = Socket(host, port, local, 0)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int) = Socket(address, port, local, 0)
    }

    private fun listener(what: String, onFail: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) { Log.w(TAG, "$what failed: $reason"); onFail() }
    }

    private suspend fun await(call: (WifiP2pManager.ActionListener) -> Unit) {
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { c ->
                call(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (c.isActive) c.resume(Unit) }
                    override fun onFailure(reason: Int) { if (c.isActive) c.resume(Unit) }
                })
            }
        }
    }
}
