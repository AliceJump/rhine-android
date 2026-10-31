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

/** A gesture has one owner: a two-finger gesture can never finish as a tap or album swipe. */
internal suspend fun PointerInputScope.detectArchiveNavigation(
    dragThreshold: Float,
    onTap: (Offset) -> Unit,
    onDrag: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
    onPanorama: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var multiplePointers = false
        var dragging = false
        var cancelled = down.isConsumed
        var zoom = 1f
        var distance = Offset.Zero
        var furthest = 0f
        var lastPoint = down.position
        try {
            while (!cancelled) {
                val event = awaitPointerEvent()
                if (!dragging && !multiplePointers && event.changes.any { it.isConsumed }) {
                    cancelled = true
                    break
                }
                if (event.changes.count { it.pressed } >= 2) {
                    multiplePointers = true
                    dragging = false
                    onDrag(0f)
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (zoom <= .82f) {
                        onPanorama()
                        break
                    }
                } else if (multiplePointers) {
                    event.changes.forEach { it.consume() }
                } else {
                    event.changes.firstOrNull { it.id == down.id }?.let { change ->
                        lastPoint = change.position
                        distance = change.position - down.position
                        furthest = maxOf(furthest, distance.getDistance())
                        if (!dragging && abs(distance.x) > viewConfiguration.touchSlop && abs(distance.x) > abs(distance.y)) {
                            dragging = true
                        } else if (!dragging && abs(distance.y) > viewConfiguration.touchSlop) {
                            // Leave vertical scrolling to the portrait page containing the shelf.
                            cancelled = true
                        }
                        if (dragging) {
                            onDrag(distance.x)
                            change.consume()
                        }
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (!cancelled && !multiplePointers) {
                if (dragging && abs(distance.x) >= dragThreshold) onSwipe(if (distance.x < 0f) 1 else -1)
                else if (!dragging && furthest <= viewConfiguration.touchSlop) onTap(lastPoint)
            }
        } finally {
            onDrag(0f)
        }
    }
}

internal suspend fun PointerInputScope.detectPanoramaNavigation(
    acceptStart: (Offset) -> Boolean,
    onTap: (Offset) -> Unit,
    onStart: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!acceptStart(down.position)) return@awaitEachGesture
        var multiplePointers = false
        var transforming = false
        var cancelled = down.isConsumed
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var furthest = 0f
        var lastPoint = down.position
        try {
            while (!cancelled) {
                val event = awaitPointerEvent()
                if (!transforming && event.changes.any { it.isConsumed }) {
                    cancelled = true
                    break
                }
                multiplePointers = multiplePointers || event.changes.count { it.pressed } >= 2
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                totalZoom *= zoom
                totalPan += pan
                furthest = maxOf(furthest, totalPan.getDistance())
                event.changes.firstOrNull { it.id == down.id }?.let { lastPoint = it.position }
                if (!transforming) {
                    val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                    if (zoomMotion > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop) {
                        transforming = true
                        onStart()
                    }
                }
                if (transforming) {
                    if (zoom != 1f || pan != Offset.Zero) {
                        onTransform(event.calculateCentroid(useCurrent = true), pan, zoom)
                    }
                    event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                }
                if (event.changes.none { it.pressed }) break
            }
            if (!cancelled && !transforming && !multiplePointers && furthest <= viewConfiguration.touchSlop) onTap(lastPoint)
        } finally {
            if (transforming) onEnd()
        }
    }
}
