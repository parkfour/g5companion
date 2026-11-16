package dev.g5.companion

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE link to the DPVR G5 over its native Eyevue GATT service (see [Eyevue]). One GATT operation at a time,
 * serialized by [gattLock]; each op waits on [pending] for its callback.
 */
@SuppressLint("MissingPermission") // entry points run only after BLUETOOTH_CONNECT/SCAN are granted
object Glasses {
    private const val TAG = "Glasses"
    private const val OP_TIMEOUT_MS = 10_000L

    data class Found(val name: String, val mac: String, val rssi: Int)

    data class State(
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val autoReconnect: Boolean = true,
        val name: String? = null,
        val mac: String? = null,
        val battery: Int? = null,
        val charging: Boolean = false,
        val mediaCount: Int? = null,
        val recordingVideo: Boolean = false,
        val recordingAudio: Boolean = false,
        val model: String? = null,
        val modelCode: String? = null, // project + customer, e.g. "S3DPVR"; the vendor's firmware lookup key
        val btVersion: String? = null, // Bluetooth/main firmware, e.g. "1.0.12"
        val ispVersion: String? = null, // camera (ISP) firmware
        val scanning: Boolean = false,
        val found: List<Found> = emptyList(),
        val message: String? = null,
    )

    sealed interface Event {
        data object AiButton : Event
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun triggerAiButton() {
        _events.tryEmit(Event.AiButton)
    }
    private val _frames = MutableSharedFlow<Eyevue.Frame>(extraBufferCapacity = 64)
    val frames = _frames.asSharedFlow()
    private val photos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

    private lateinit var app: Application
    private lateinit var prefs: Prefs
    private val gattLock = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var pendingServices: CompletableDeferred<Int>? = null
    @Volatile private var pendingMtu: CompletableDeferred<Int>? = null
    private val decoder = Eyevue.Decoder()
    private val photoAssembler = Eyevue.PhotoAssembler()
    private var keepConnected = false
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var loopJob: Job? = null

    fun init(application: Application) {
        app = application
        prefs = Prefs(application)
        _state.update { it.copy(name = prefs.lastDeviceName, mac = prefs.lastDeviceMac) }
        ContextCompat.registerReceiver(
            application, AdapterReceiver(), IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private val adapter: BluetoothAdapter? get() = app.getSystemService(BluetoothManager::class.java).adapter

    // ---------- scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.device.name?.takeIf { it.isNotBlank() } ?: r.scanRecord?.deviceName ?: return
            addFound(Found(name, r.device.address, r.rssi))
        }

        override fun onScanFailed(errorCode: Int) = fail("Scan failed ($errorCode)")
    }

    private fun addFound(f: Found) = _state.update { s ->
        if (s.found.any { it.mac == f.mac }) s else s.copy(found = (s.found + f).sortedByDescending { it.rssi })
    }

    fun startScan(context: Context) {
        val a = adapter ?: return fail("Bluetooth unavailable")
        val scanner = a.bluetoothLeScanner ?: return fail("Bluetooth is off")
        _state.update { it.copy(scanning = true, found = emptyList()) }
        // Glasses already linked to the phone often stop advertising, so offer bonded G5s directly.
        a.bondedDevices.filter { d -> d.name?.let { it.contains("G5", true) || it.contains("DPVR", true) } == true }
            .forEach { addFound(Found(it.name, it.address, 0)) }
        runCatching { scanner.startScan(scanCallback) }.onFailure { fail("Scan failed: ${it.message}") }
        scope.launch { delay(15_000); stopScan(context) }
    }

    fun stopScan(@Suppress("UNUSED_PARAMETER") context: Context) {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _state.update { it.copy(scanning = false) }
    }

    // ---------- connection ----------

    fun connect(mac: String, name: String?) {
        prefs.lastDeviceMac = mac
        prefs.lastDeviceName = name
        prefs.userDisconnected = false
        keepConnected = true
        _state.update { it.copy(mac = mac, name = name, connecting = true, autoReconnect = true, message = null) }
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            val ok = runCatching { open(mac) }.onFailure { Log.w(TAG, "connect failed", it) }.getOrDefault(false)
            if (ok) onReady() else {
                closeGatt()
                _state.update { it.copy(connecting = false) }
                scheduleReconnect()
            }
        }
    }

    fun ensureConnected() {
        val mac = prefs.lastDeviceMac ?: return
        if (prefs.userDisconnected) return // user tapped BT Disconnect; don't override them
        keepConnected = true
        if (!_state.value.connected && !_state.value.connecting) connect(mac, prefs.lastDeviceName)
    }

    fun disconnect() {
        keepConnected = false
        prefs.userDisconnected = true
        reconnectJob?.cancel(); connectJob?.cancel(); loopJob?.cancel()
        closeGatt()
        _state.update { it.copy(connected = false, connecting = false, autoReconnect = false, recordingVideo = false, recordingAudio = false) }
    }

    fun forget() {
        keepConnected = false
        reconnectJob?.cancel(); connectJob?.cancel(); loopJob?.cancel()
        closeGatt()
        prefs.lastDeviceMac = null
        prefs.lastDeviceName = null
        prefs.userDisconnected = false
        _state.update { State() }
    }

    private fun scheduleReconnect() {
        if (!keepConnected || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var backoff = 5_000L
            while (isActive && keepConnected && !_state.value.connected) {
                delay(backoff)
                val mac = prefs.lastDeviceMac ?: break
                if (!_state.value.connecting && !_state.value.connected) {
                    Log.i(TAG, "Auto-reconnect")
                    connect(mac, prefs.lastDeviceName)
                }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** Connect, discover, enable AA14 + AA15 notifications. False if anything is missing. */
    private suspend fun open(mac: String): Boolean = gattLock.withLock {
        closeGatt()
        val device = adapter?.getRemoteDevice(mac) ?: return false
        val st = op { gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE) }
        if (st != BluetoothProfile.STATE_CONNECTED) return false
        val g = gatt ?: return false
        if (opServices(g) != BluetoothGatt.GATT_SUCCESS) return false
        val svc = g.getService(Eyevue.SERVICE) ?: run { fail("These glasses don't expose the G5 service"); return false }
        for (uuid in listOf(Eyevue.NOTIFY, Eyevue.PHOTO)) {
            val c = svc.getCharacteristic(uuid) ?: return false
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(Eyevue.CCCD) ?: return false
            if (op { g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) } != BluetoothGatt.GATT_SUCCESS) return false
        }
        opMtu(g, 512)
        true
    }

    /** Starts one GATT call and waits for its callback (status, or new state for connect). -1 on timeout. */
    private suspend fun op(start: () -> Unit): Int {
        val d = CompletableDeferred<Int>()
        pending = d
        start()
        return withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: -1
    }

    private suspend fun opServices(g: BluetoothGatt): Int {
        val d = CompletableDeferred<Int>()
        pendingServices = d
        if (!g.discoverServices()) return -1
        return withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: -1
    }

    private suspend fun opMtu(g: BluetoothGatt, mtu: Int): Int {
        val d = CompletableDeferred<Int>()
        pendingMtu = d
        if (!g.requestMtu(mtu)) return -1
        return withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: -1
    }

    private fun complete(value: Int) {
        pending?.let { pending = null; it.complete(value) }
    }

    private fun closeGatt() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        decoder.reset()
    }

    private fun onReady() {
        Log.i(TAG, "ready")
        _state.update { it.copy(connected = true, connecting = false, message = null) }
        scope.launch {
            send(Eyevue.setTime())
            send(Eyevue.customer())
            send(Eyevue.voiceStatus())
            send(Eyevue.deviceInfo())
            refreshStatus()
        }
    }

    private fun onDisconnected() {
        closeGatt()
        complete(-2) // never mistaken for GATT_SUCCESS (0)
        pendingServices?.let { pendingServices = null; it.complete(-2) }
        pendingMtu?.let { pendingMtu = null; it.complete(-2) }
        _state.update { it.copy(connected = false, connecting = false, recordingVideo = false, recordingAudio = false) }
        scheduleReconnect()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> complete(newState)
                BluetoothProfile.STATE_DISCONNECTED -> if (g == gatt || gatt == null) onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            pendingServices?.let { pendingServices = null; it.complete(status) } ?: complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            pendingMtu?.let { pendingMtu = null; it.complete(status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                Eyevue.NOTIFY -> decoder.append(value).forEach(::handle)
                Eyevue.PHOTO -> photoAssembler.append(value)?.let { photos.tryEmit(it) }
            }
        }
    }

    private fun handle(f: Eyevue.Frame) {
        Log.d(TAG, "rx $f")
        _frames.tryEmit(f)
        Eyevue.parseBattery(f)?.let { (pct, chg) -> _state.update { it.copy(battery = pct.coerceIn(0, 100), charging = chg) } }
        when (f.cmd) {
            Eyevue.GET_MEDIA_COUNT, Eyevue.THUMB_COUNT -> Eyevue.u16(f)?.let { n -> _state.update { it.copy(mediaCount = n) } }
            Eyevue.DEVICE_STATUS -> if (f.payload.size >= 9) { // [0] photo [1] audio [2] video … [8] worn
                val video = f.u(2) == 1
                if (_state.value.recordingVideo && !video) onClipEnded()
                _state.update { it.copy(recordingVideo = video, recordingAudio = f.u(1) == 1) }
            }
            Eyevue.CUSTOMER -> if (f.payload.size >= 8) {
                fun part(o: Int) = String(f.payload.copyOfRange(o, o + 4).filter { it != 0.toByte() }.toByteArray())
                val project = part(0)
                _state.update { it.copy(model = project, modelCode = project + part(4)) }
            }
            Eyevue.DEVICE_INFO -> if (f.payload.size >= 6) {
                fun v(o: Int) = "${f.u(o)}.${f.u(o + 1)}.${f.u(o + 2)}"
                _state.update { it.copy(btVersion = v(0), ispVersion = v(3)) }
            }
            Eyevue.GET_VOICE_STATUS -> {
                // bit 1 (value 2) set = on-device wake word disabled. Assert the user's choice,
                // preserving the other status bits. Re-asserted on every connect (see :251).
                val v = f.u(0) ?: return
                val want = if (prefs.wakeWordEnabled) v and 2.inv() else v or 2
                if (want != v) scope.launch { send(Eyevue.frame(Eyevue.SET_VOICE_STATUS, want)) }
            }
            Eyevue.STREAM_START -> _events.tryEmit(Event.AiButton)
        }
    }

    // ---------- commands ----------

    /** Re-query voice status so the [Eyevue.GET_VOICE_STATUS] handler re-applies [Prefs.wakeWordEnabled] now. */
    fun refreshVoiceStatus() { scope.launch { send(Eyevue.voiceStatus()) } }

    /** Writes one framed command to AA13. Replies arrive asynchronously via [frames]. */
    suspend fun send(packet: ByteArray): Boolean = gattLock.withLock {
        val g = gatt ?: return false
        if (!_state.value.connected) return false
        val c = g.getService(Eyevue.SERVICE)?.getCharacteristic(Eyevue.WRITE) ?: return false
        Log.d(TAG, "tx ${packet.hex()}")
        val status = op { g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
        (status == BluetoothGatt.GATT_SUCCESS).also { if (!it) Log.w(TAG, "write failed ($status)") }
    }

    suspend fun refreshStatus() {
        send(Eyevue.battery())
        send(Eyevue.mediaCount())
    }

    suspend fun refreshMediaCount() = send(Eyevue.mediaCount())

    suspend fun photo() {
        send(Eyevue.photo())
        scope.launch { delay(1500); refreshMediaCount() }
    }

    suspend fun startVideo() {
        if (send(Eyevue.video(true))) _state.update { it.copy(recordingVideo = true) }
    }

    suspend fun stopVideo() {
        loopJob?.cancel()
        send(Eyevue.video(false))
        _state.update { it.copy(recordingVideo = false) }
        scope.launch { delay(1500); refreshMediaCount() }
    }

    /** Device reported recording stopped on its own (per-file cap). In loop mode, start the next clip. */
    private fun onClipEnded() {
        if (!prefs.videoLoop) return
        loopJob?.cancel()
        // ponytail: fixed 500 ms settle before restarting; tune if the firmware rejects an immediate start.
        loopJob = scope.launch { delay(500); startVideo() }
    }

    suspend fun startAudio() {
        if (send(Eyevue.audio(true))) _state.update { it.copy(recordingAudio = true) }
    }

    suspend fun stopAudio() {
        send(Eyevue.audio(false))
        _state.update { it.copy(recordingAudio = false) }
        scope.launch { delay(1500); refreshMediaCount() }
    }

    suspend fun stopVoiceAssistant() = send(Eyevue.stopVoice())

    /** Takes a photo and returns the JPEG the glasses stream back over AA15. */
    suspend fun capturePhoto(timeoutMs: Long = 12_000): ByteArray? = coroutineScope {
        val jpg = async(start = CoroutineStart.UNDISPATCHED) { photos.first() }
        if (!send(Eyevue.photo())) { jpg.cancel(); return@coroutineScope null }
        withTimeoutOrNull(timeoutMs) { jpg.await() }.also { if (it == null) jpg.cancel() }
    }

    fun setMessage(msg: String?) = _state.update { it.copy(message = msg) }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(message = msg, connecting = false, scanning = false) }
    }

    private class AdapterReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> onDisconnected()
                BluetoothAdapter.STATE_ON -> if (keepConnected) prefs.lastDeviceMac?.let { connect(it, prefs.lastDeviceName) }
            }
        }
    }
}
