package dev.g5.companion.ui

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.g5.companion.AUDIO_DIR
import dev.g5.companion.IMAGE_VIDEO_DIR
import dev.g5.companion.MediaSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaKind { IMAGE, VIDEO, AUDIO }

data class MediaItem(val uri: Uri, val name: String, val kind: MediaKind, val date: Long)

@Composable
fun GalleryScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val sync by MediaSync.progress.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sync.running, sync.done, reload) {
        items = withContext(Dispatchers.IO) { queryMedia(ctx) }
        selected = selected.filterTo(mutableSetOf()) { u -> items.any { it.uri == u } }
    }
    // Files this install didn't create (e.g. after a reinstall) need the system's delete confirmation.
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            ActionBar(
                title = "${selected.size} selected",
                navIcon = Icons.Filled.Close, navLabel = "Cancel", onNav = { selected = emptySet() },
                onShare = { share(ctx, items.filter { it.uri in selected }) },
                onDelete = { pendingDelete = items.filter { it.uri in selected } },
            )
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No synced media yet. Use Sync All Media on the Glasses tab.") }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize()) {
                items(items, key = { it.uri.toString() }) { item ->
                    fun toggle() { selected = if (item.uri in selected) selected - item.uri else selected + item.uri }
                    Thumb(
                        item,
                        selected = item.uri in selected,
                        onClick = { if (selecting) toggle() else openIndex = items.indexOf(item) },
                        onLongClick = { toggle() },
                    )
                }
            }
        }
    }

    // Viewer stays open across deletes, landing on the neighbouring item; it closes when nothing is left.
    openIndex?.let { i ->
        if (items.isEmpty()) openIndex = null
        else Viewer(
            items, i.coerceIn(0, items.lastIndex),
            onBack = { openIndex = null },
            onShare = { share(ctx, listOf(it)) },
            onDelete = { pendingDelete = listOf(it) },
        )
    }

    pendingDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (doomed.size == 1) "Delete ${doomed[0].name}?" else "Delete ${doomed.size} items?") },
            text = { Text("Removes ${if (doomed.size == 1) "it" else "them"} from this phone. The glasses are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selected = emptySet()
                    scope.launch {
                        val needConsent = withContext(Dispatchers.IO) {
                            doomed.filter { item ->
                                try { ctx.contentResolver.delete(item.uri, null, null); false } catch (_: SecurityException) { true }
                            }
                        }
                        if (needConsent.isNotEmpty()) {
                            val req = MediaStore.createDeleteRequest(ctx.contentResolver, needConsent.map { it.uri })
                            systemDelete.launch(IntentSenderRequest.Builder(req.intentSender).build())
                        }
                        reload++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Top bar used by both multi-select and the viewer: navigation, title, share, delete. */
@Composable
private fun ActionBar(
    title: String,
    navIcon: androidx.compose.ui.graphics.vector.ImageVector,
    navLabel: String,
    onNav: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(modifier.fillMaxWidth(), color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNav) { Icon(navIcon, navLabel) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }
}

private fun mimeOf(item: MediaItem) = when (item.kind) {
    MediaKind.IMAGE -> "image/*"
    MediaKind.VIDEO -> "video/*"
    MediaKind.AUDIO -> "audio/*"
}

/** Standard Android share sheet; receiving apps get temporary read access to just these files. */
private fun share(ctx: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val type = items.map(::mimeOf).distinct().singleOrNull() ?: "*/*"
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.apply {
        setType(type)
        clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Thumb(item: MediaItem, selected: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(item.uri, Size(300, 300), null) }.getOrNull() }
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.padding(2.dp).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, accent) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (item.kind == MediaKind.AUDIO) "♪" else "…", style = MaterialTheme.typography.headlineMedium)
        if (item.kind != MediaKind.IMAGE) {
            Text(
                if (item.kind == MediaKind.VIDEO) "▶" else item.name,
                Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.25f)))
            Icon(
                Icons.Filled.CheckCircle, "Selected",
                Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White, MaterialTheme.shapes.extraLarge),
                tint = accent,
            )
        }
    }
}

/** Full-screen viewer: swipe left/right between items; top bar has back, share and delete. */
@Composable
private fun Viewer(items: List<MediaItem>, start: Int, onBack: () -> Unit, onShare: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    val currentZoomState = remember(pager.currentPage) { ZoomPanState() }
    var showControls by remember { mutableStateOf(true) }
    val current = items.getOrNull(pager.currentPage.coerceIn(0, items.lastIndex))
    val isZoomable = current?.kind == MediaKind.IMAGE || current?.kind == MediaKind.VIDEO

    Dialog(onBack, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                key = { items[it].uri.toString() },
                userScrollEnabled = !currentZoomState.isZoomed,
            ) { page ->
                val item = items[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val pageZoomState = if (page == pager.currentPage) currentZoomState else rememberZoomPanState()
                    val zoomEnabled = item.kind == MediaKind.IMAGE || item.kind == MediaKind.VIDEO

                    ZoomableBox(
                        modifier = Modifier.fillMaxSize(),
                        state = pageZoomState,
                        enabled = zoomEnabled,
                        onTap = { showControls = !showControls },
                    ) {
                        when {
                            item.kind == MediaKind.IMAGE -> FullImage(item)
                            // Only the settled page plays; neighbours show a still so swiping doesn't start audio.
                            page == pager.settledPage -> Player(item)
                            else -> Thumb(item, onClick = {}, onLongClick = {})
                        }
                    }
                }
            }
            if (current != null) {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Box(Modifier.background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding()) {
                        ActionBar(
                            title = current.name,
                            navIcon = Icons.AutoMirrored.Filled.ArrowBack, navLabel = "Back", onNav = onBack,
                            onShare = { onShare(current) }, onDelete = { onDelete(current) },
                            container = Color.Transparent, content = Color.White,
                        )
                    }
                }
            }
            if (isZoomable) {
                AnimatedVisibility(
                    visible = showControls || currentZoomState.isZoomed,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
                ) {
                    ZoomControlsOverlay(
                        state = currentZoomState,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullImage(item: MediaItem) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, item.uri)) }.getOrNull()
        }
    }
    bmp?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

/** VideoView plays MP4 and, with a MediaController, WAV/MP3/Ogg audio as well. */
@Composable
private fun Player(item: MediaItem) {
    AndroidView(
        factory = { c ->
            VideoView(c).apply {
                val controller = MediaController(c).also { it.setAnchorView(this) }
                setMediaController(controller)
                setVideoURI(item.uri)
                setOnPreparedListener { start(); controller.show(0) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        onRelease = { it.stopPlayback() },
    )
}

private fun queryMedia(ctx: Context): List<MediaItem> {
    fun query(collection: Uri, dir: String, kind: MediaKind): List<MediaItem> =
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir%"), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(MediaItem(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), kind, c.getLong(2)))
            }
        }.orEmpty()

    val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
    return (
        query(MediaStore.Images.Media.getContentUri(vol), IMAGE_VIDEO_DIR, MediaKind.IMAGE) +
            query(MediaStore.Video.Media.getContentUri(vol), IMAGE_VIDEO_DIR, MediaKind.VIDEO) +
            query(MediaStore.Audio.Media.getContentUri(vol), AUDIO_DIR, MediaKind.AUDIO)
        ).sortedByDescending { it.date }
}
