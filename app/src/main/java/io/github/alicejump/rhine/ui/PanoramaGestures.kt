package io.github.alicejump.rhine.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.ln

/** One gesture keeps its owner even while the same cases move into another arrangement. */
internal suspend fun PointerInputScope.detectShelfNavigation(
    acceptStart: (Offset) -> Boolean,
    expansion: () -> Float,
    dragThreshold: Float,
    onTap: (Offset) -> Unit,
    onDrag: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
    onNormalScroll: (Float) -> Unit,
    onExpansion: (Float) -> Unit,
    onExpansionEnd: (Boolean) -> Unit,
    onPanoramaStart: () -> Unit,
    onPanoramaTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onPanoramaEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!acceptStart(down.position)) return@awaitEachGesture
        val startedExpanded = expansion() >= .999f
        var progress = expansion()
        var multiplePointers = false
        var expanding = false
        var horizontal = false
        var vertical = false
        var transforming = false
        var cancelled = down.isConsumed
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var furthest = 0f
        var lastPoint = down.position
        try {
            while (!cancelled) {
                val event = awaitPointerEvent()
                if (!horizontal && !vertical && !expanding && !transforming && event.changes.any { it.isConsumed }) {
                    cancelled = true
                    break
                }
                val multi = event.changes.count { it.pressed } >= 2
                multiplePointers = multiplePointers || multi
                val pan = event.calculatePan()
                val zoom = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                totalPan += pan
                totalZoom *= zoom
                furthest = maxOf(furthest, totalPan.getDistance())
                event.changes.firstOrNull { it.id == down.id }?.let { lastPoint = it.position }
                if (!startedExpanded) {
                    if (multi) {
                        expanding = true
                        horizontal = false
                        vertical = false
                        onDrag(0f)
                        progress = (progress - ln(zoom) / .65f).coerceIn(0f, 1f)
                        onExpansion(progress)
                        event.changes.forEach { it.consume() }
                    } else if (multiplePointers) {
                        // The remaining finger still belongs to the pinch; never select on lift.
                        event.changes.forEach { it.consume() }
                    } else {
                        if (!horizontal && !vertical && totalPan.getDistance() > viewConfiguration.touchSlop) {
                            horizontal = abs(totalPan.x) > abs(totalPan.y)
                            vertical = !horizontal
                        }
                        if (horizontal) onDrag(totalPan.x.coerceIn(-4f * dragThreshold, 4f * dragThreshold))
                        if (vertical) onNormalScroll(-pan.y)
                        if (horizontal || vertical) event.changes.forEach { it.consume() }
                    }
                } else {
                    if (!transforming) {
                        val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                        if (zoomMotion > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop) {
                            transforming = true
                            onPanoramaStart()
                        }
                    }
                    if (transforming) {
                        if (zoom != 1f || pan != Offset.Zero) onPanoramaTransform(event.calculateCentroid(), pan, zoom)
                        event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (!cancelled && !multiplePointers && !transforming && !vertical) {
                if (horizontal && abs(totalPan.x) >= dragThreshold) onSwipe(if (totalPan.x < 0f) 1 else -1)
                else if (!horizontal && furthest <= viewConfiguration.touchSlop) onTap(lastPoint)
            }
        } finally {
            onDrag(0f)
            if (expanding) onExpansionEnd(progress >= .35f)
            if (transforming) onPanoramaEnd()
        }
    }
}
