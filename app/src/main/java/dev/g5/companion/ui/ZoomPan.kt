package dev.g5.companion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * Holds zoom (scale) and pan (offset) state with bounds clamping and animations.
 */
class ZoomPanState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
) {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    var containerSize by mutableStateOf(IntSize.Zero)
    var animationJob: Job? = null

    val isZoomed: Boolean
        get() = scale > 1.05f

    /** Maximum allowable pan offset based on container dimensions and current scale. */
    fun maxOffsetX(currentScale: Float = scale): Float =
        if (containerSize.width <= 0) 0f
        else maxOf(0f, (containerSize.width * currentScale - containerSize.width) / 2f)

    fun maxOffsetY(currentScale: Float = scale): Float =
        if (containerSize.height <= 0) 0f
        else maxOf(0f, (containerSize.height * currentScale - containerSize.height) / 2f)

    /** Apply interactive two-finger transform (pinch zoom + pan). */
    fun applyTransform(centroid: Offset, panDelta: Offset, zoomDelta: Float) {
        animationJob?.cancel()
        val oldScale = scale
        val newScale = (oldScale * zoomDelta).coerceIn(minScale * 0.75f, maxScale * 1.5f)
        val effectiveZoom = if (oldScale > 0f) newScale / oldScale else 1f

        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val centroidFromCenter = centroid - center
        val newOffsetX = offsetX * effectiveZoom + centroidFromCenter.x * (1f - effectiveZoom) + panDelta.x
        val newOffsetY = offsetY * effectiveZoom + centroidFromCenter.y * (1f - effectiveZoom) + panDelta.y

        val limitX = maxOffsetX(newScale)
        val limitY = maxOffsetY(newScale)

        scale = newScale
        offsetX = if (newScale <= 1f) 0f else newOffsetX.coerceIn(-limitX, limitX)
        offsetY = if (newScale <= 1f) 0f else newOffsetY.coerceIn(-limitY, limitY)
    }

    /** Apply interactive single-finger pan when zoomed in. */
    fun applyPan(panDelta: Offset) {
        if (!isZoomed) return
        animationJob?.cancel()
        val limitX = maxOffsetX(scale)
        val limitY = maxOffsetY(scale)
        offsetX = (offsetX + panDelta.x).coerceIn(-limitX, limitX)
        offsetY = (offsetY + panDelta.y).coerceIn(-limitY, limitY)
    }

    /** Smoothly animate scale and offsets to target values. */
    suspend fun animateTo(targetScale: Float, targetOffsetX: Float, targetOffsetY: Float) = coroutineScope {
        animationJob?.cancel()
        if (coroutineContext[MonotonicFrameClock] != null) {
            val sAnim = Animatable(scale)
            val xAnim = Animatable(offsetX)
            val yAnim = Animatable(offsetY)
            val spec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
            val job = launch {
                launch {
                    sAnim.animateTo(targetScale, spec) {
                        scale = value
                    }
                }
                launch {
                    xAnim.animateTo(targetOffsetX, spec) {
                        offsetX = value
                    }
                }
                launch {
                    yAnim.animateTo(targetOffsetY, spec) {
                        offsetY = value
                    }
                }
            }
            animationJob = job
            job.join()
        } else {
            scale = targetScale
            offsetX = targetOffsetX
            offsetY = targetOffsetY
        }
    }

    /** Settle back into valid bounds after gesture ends. */
    suspend fun settle() {
        val clampedScale = scale.coerceIn(minScale, maxScale)
        val limitX = maxOffsetX(clampedScale)
        val limitY = maxOffsetY(clampedScale)
        val targetX = if (clampedScale <= 1.01f) 0f else offsetX.coerceIn(-limitX, limitX)
        val targetY = if (clampedScale <= 1.01f) 0f else offsetY.coerceIn(-limitY, limitY)

        if (clampedScale != scale || targetX != offsetX || targetY != offsetY) {
            animateTo(clampedScale, targetX, targetY)
        }
    }

    /** Toggle zoom on double-tap: zooms into 2.5x around tap position or resets back to 1x. */
    suspend fun toggleZoom(tapPos: Offset, targetScale: Float = 2.5f) {
        if (isZoomed) {
            reset(animated = true)
        } else {
            val clampedScale = targetScale.coerceIn(minScale, maxScale)
            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
            val centroidFromCenter = tapPos - center
            val rawTargetX = centroidFromCenter.x * (1f - clampedScale)
            val rawTargetY = centroidFromCenter.y * (1f - clampedScale)
            val limitX = maxOffsetX(clampedScale)
            val limitY = maxOffsetY(clampedScale)
            animateTo(
                targetScale = clampedScale,
                targetOffsetX = rawTargetX.coerceIn(-limitX, limitX),
                targetOffsetY = rawTargetY.coerceIn(-limitY, limitY),
            )
        }
    }

    /** Zoom in by one step (default +0.5x). */
    suspend fun zoomIn(step: Float = 0.5f) {
        val nextScale = (scale + step).coerceIn(minScale, maxScale)
        val factor = if (scale > 0f) nextScale / scale else 1f
        val limitX = maxOffsetX(nextScale)
        val limitY = maxOffsetY(nextScale)
        animateTo(
            targetScale = nextScale,
            targetOffsetX = (offsetX * factor).coerceIn(-limitX, limitX),
            targetOffsetY = (offsetY * factor).coerceIn(-limitY, limitY),
        )
    }

    /** Zoom out by one step (default -0.5x). */
    suspend fun zoomOut(step: Float = 0.5f) {
        val nextScale = (scale - step).coerceIn(minScale, maxScale)
        if (nextScale <= 1.01f) {
            reset(animated = true)
        } else {
            val factor = if (scale > 0f) nextScale / scale else 1f
            val limitX = maxOffsetX(nextScale)
            val limitY = maxOffsetY(nextScale)
            animateTo(
                targetScale = nextScale,
                targetOffsetX = (offsetX * factor).coerceIn(-limitX, limitX),
                targetOffsetY = (offsetY * factor).coerceIn(-limitY, limitY),
            )
        }
    }

    /** Reset to 1x and (0,0). */
    suspend fun reset(animated: Boolean = true) {
        if (animated) {
            animateTo(1f, 0f, 0f)
        } else {
            animationJob?.cancel()
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }
}

@Composable
fun rememberZoomPanState(
    minScale: Float = 1f,
    maxScale: Float = 5f,
): ZoomPanState = remember { ZoomPanState(minScale, maxScale) }

/**
 * A container Box providing pinch-to-zoom, pan drag, and double-tap zoom gestures.
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    state: ZoomPanState = rememberZoomPanState(),
    enabled: Boolean = true,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var singleTapJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.containerSize = it }
            .then(
                if (enabled) {
                    Modifier.pointerInput(state) {
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero

                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val firstDownPos = firstDown.position
                            val firstDownTime = System.currentTimeMillis()
                            var isMultiTouch = false
                            var hasMoved = false
                            var prevCentroid = Offset.Zero
                            var prevDistance = 0f

                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val active = event.changes.filter { it.pressed }

                                if (active.size >= 2) {
                                    isMultiTouch = true
                                    hasMoved = true
                                    val p0 = active[0].position
                                    val p1 = active[1].position
                                    val centroid = (p0 + p1) / 2f
                                    val dx = p0.x - p1.x
                                    val dy = p0.y - p1.y
                                    val distance = sqrt(dx * dx + dy * dy)

                                    if (prevDistance > 0f) {
                                        val zoomDelta = distance / prevDistance
                                        val panDelta = centroid - prevCentroid
                                        state.applyTransform(centroid, panDelta, zoomDelta)
                                    }
                                    prevCentroid = centroid
                                    prevDistance = distance

                                    event.changes.forEach { it.consume() }
                                } else if (active.size == 1) {
                                    prevDistance = 0f
                                    val change = active[0]
                                    val panDelta = change.position - change.previousPosition
                                    val dist = (change.position - firstDownPos).getDistance()
                                    if (dist > viewConfiguration.touchSlop) {
                                        hasMoved = true
                                    }

                                    if (state.isZoomed && hasMoved) {
                                        state.applyPan(panDelta)
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (isMultiTouch || (hasMoved && state.isZoomed)) {
                                scope.launch { state.settle() }
                            } else if (!hasMoved && !isMultiTouch) {
                                val upTime = System.currentTimeMillis()
                                val duration = upTime - firstDownTime
                                if (duration < 300) {
                                    val isDoubleTap = (upTime - lastTapTime < 350) &&
                                        ((firstDownPos - lastTapPos).getDistance() < 120f)
                                    if (isDoubleTap) {
                                        singleTapJob?.cancel()
                                        lastTapTime = 0L
                                        scope.launch { state.toggleZoom(firstDownPos) }
                                    } else {
                                        lastTapTime = upTime
                                        lastTapPos = firstDownPos
                                        singleTapJob?.cancel()
                                        singleTapJob = scope.launch {
                                            delay(250)
                                            onTap?.invoke()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offsetX
                    translationY = state.offsetY
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * Modern floating pill showing zoom in, zoom out, current zoom level, and reset controls.
 */
@Composable
fun ZoomControlsOverlay(
    state: ZoomPanState,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val formattedScale = String.format(Locale.US, "%.1fx", state.scale)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Zoom Out (-)
            IconButton(
                onClick = { scope.launch { state.zoomOut() } },
                enabled = state.scale > state.minScale,
            ) {
                Text(
                    text = "−",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    color = if (state.scale > state.minScale) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }

            // Zoom Badge (tap to reset)
            TextButton(
                onClick = {
                    onReset?.invoke() ?: scope.launch { state.reset() }
                },
            ) {
                Text(
                    text = formattedScale,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.isZoomed) MaterialTheme.colorScheme.primary else Color.White,
                )
            }

            // Zoom In (+)
            IconButton(
                onClick = { scope.launch { state.zoomIn() } },
                enabled = state.scale < state.maxScale,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Zoom In",
                    tint = if (state.scale < state.maxScale) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }

            // If zoomed in, show explicit Reset button
            if (state.isZoomed) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        onReset?.invoke() ?: scope.launch { state.reset() }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset Zoom",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
