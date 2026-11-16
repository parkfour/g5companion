package dev.g5.companion.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPanTest {

    @Test
    fun `initial state is not zoomed with zero offset`() {
        val state = ZoomPanState()
        assertEquals(1f, state.scale, 0.001f)
        assertEquals(0f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `transform clamps within bounds and updates scale`() = runBlocking {
        val state = ZoomPanState(minScale = 1f, maxScale = 5f)
        state.containerSize = IntSize(1000, 2000)

        // Pinch to 2x at center
        val center = Offset(500f, 1000f)
        state.applyTransform(centroid = center, panDelta = Offset.Zero, zoomDelta = 2f)

        assertEquals(2f, state.scale, 0.01f)
        assertTrue(state.isZoomed)
        assertEquals(0f, state.offsetX, 0.01f)
        assertEquals(0f, state.offsetY, 0.01f)

        // Max pan offset at 2x: (1000 * 2 - 1000) / 2 = 500
        assertEquals(500f, state.maxOffsetX(), 0.01f)
        assertEquals(1000f, state.maxOffsetY(), 0.01f)

        // Pan right by 200px
        state.applyPan(Offset(200f, 0f))
        assertEquals(200f, state.offsetX, 0.01f)

        // Pan beyond max offset
        state.applyPan(Offset(800f, 0f))
        assertEquals(500f, state.offsetX, 0.01f) // Clamped to 500

        // Pan negative beyond -max offset
        state.applyPan(Offset(-1200f, 0f))
        assertEquals(-500f, state.offsetX, 0.01f) // Clamped to -500
    }

    @Test
    fun `pinch off-center shifts offset towards focal point`() {
        val state = ZoomPanState(minScale = 1f, maxScale = 5f)
        state.containerSize = IntSize(1000, 1000)

        // Pinch focal point near bottom-right corner (800, 800)
        // Center is (500, 500). CentroidFromCenter is (300, 300).
        state.applyTransform(centroid = Offset(800f, 800f), panDelta = Offset.Zero, zoomDelta = 2f)

        assertEquals(2f, state.scale, 0.01f)
        // newOffset = centroidFromCenter * (1 - 2) = (300, 300) * (-1) = (-300, -300)
        assertEquals(-300f, state.offsetX, 0.01f)
        assertEquals(-300f, state.offsetY, 0.01f)
    }

    @Test
    fun `applyPan ignored when not zoomed`() {
        val state = ZoomPanState()
        state.containerSize = IntSize(1000, 1000)
        assertFalse(state.isZoomed)

        state.applyPan(Offset(150f, 150f))
        assertEquals(0f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)
    }

    @Test
    fun `settle clamps over-scaled or under-scaled states`() = runBlocking {
        val state = ZoomPanState(minScale = 1f, maxScale = 4f)
        state.containerSize = IntSize(1000, 1000)

        // Simulate transient under-zoom during active pinch (e.g. 0.8x)
        state.applyTransform(centroid = Offset(500f, 500f), panDelta = Offset.Zero, zoomDelta = 0.8f)
        assertEquals(0.8f, state.scale, 0.01f)

        state.settle()
        assertEquals(1f, state.scale, 0.01f)
        assertEquals(0f, state.offsetX, 0.01f)
        assertFalse(state.isZoomed)

        // Simulate transient over-zoom (e.g. 5x when maxScale is 4x)
        state.applyTransform(centroid = Offset(500f, 500f), panDelta = Offset.Zero, zoomDelta = 5f)
        assertTrue(state.scale > 4f)

        state.settle()
        assertEquals(4f, state.scale, 0.01f)
    }

    @Test
    fun `reset returns state to 1x and zero offsets`() = runBlocking {
        val state = ZoomPanState()
        state.containerSize = IntSize(1000, 2000)
        state.applyTransform(centroid = Offset(500f, 1000f), panDelta = Offset.Zero, zoomDelta = 3f)
        state.applyPan(Offset(100f, 100f))

        assertTrue(state.isZoomed)
        state.reset(animated = false)

        assertEquals(1f, state.scale, 0.001f)
        assertEquals(0f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `double tap toggles between zoomed and reset`() = runBlocking {
        val state = ZoomPanState()
        state.containerSize = IntSize(1000, 1000)

        // Double tap at center
        state.toggleZoom(Offset(500f, 500f), targetScale = 2.5f)
        assertEquals(2.5f, state.scale, 0.01f)
        assertTrue(state.isZoomed)

        // Double tap again resets
        state.toggleZoom(Offset(500f, 500f))
        assertEquals(1f, state.scale, 0.01f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `double tap off-center shifts toward tap position`() = runBlocking {
        val state = ZoomPanState()
        state.containerSize = IntSize(1000, 1000)

        // Double tap at (700, 500). Center is (500, 500). dx = 200.
        // targetOffset = 200 * (1 - 2.5) = -300
        state.toggleZoom(Offset(700f, 500f), targetScale = 2.5f)
        assertEquals(2.5f, state.scale, 0.01f)
        assertEquals(-300f, state.offsetX, 0.01f)
        assertEquals(0f, state.offsetY, 0.01f)
    }

    @Test
    fun `step zoom in and out modifies scale within bounds`() = runBlocking {
        val state = ZoomPanState(minScale = 1f, maxScale = 3f)
        state.containerSize = IntSize(1000, 1000)

        state.zoomIn(0.5f)
        assertEquals(1.5f, state.scale, 0.01f)

        state.zoomIn(1.0f)
        assertEquals(2.5f, state.scale, 0.01f)

        // Hitting max scale
        state.zoomIn(1.0f)
        assertEquals(3.0f, state.scale, 0.01f)

        state.zoomOut(0.5f)
        assertEquals(2.5f, state.scale, 0.01f)

        state.zoomOut(2.0f) // drops below 1.01f, resets to 1f
        assertEquals(1f, state.scale, 0.01f)
        assertFalse(state.isZoomed)
    }
}
