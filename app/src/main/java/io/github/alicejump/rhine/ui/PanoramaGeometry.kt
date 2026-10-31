package io.github.alicejump.rhine.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Geometry is kept in pixels; the camera points at a position in the unscaled album room. */
internal data class PanoramaCamera(val x: Float, val y: Float, val zoom: Float = 1f)

internal data class PanoramaPoint(val x: Float, val y: Float)

internal data class PanoramaRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal data class PanoramaPlacement(val index: Int, val x: Float, val y: Float, val size: Float) {
    // This rectangle is inside the front face, rather than in the sprite's transparent margins.
    val accessibleBounds: PanoramaRect get() = PanoramaRect(
        x + size * 185f / 600f, y + size * 210f / 600f,
        x + size * 410f / 600f, y + size * 400f / 600f,
    )
    val caseBounds: PanoramaRect get() = PanoramaRect(
        x + size * 128f / 600f, y + size * 100f / 600f,
        x + size * 472f / 600f, y + size * 518f / 600f,
    )
    fun contains(x: Float, y: Float): Boolean {
        val px = (x - this.x) / size * 600f
        val py = (y - this.y) / size * 600f
        if (px !in 128f..472f) return false
        val progress = (px - 128f) / 344f
        return py >= 100f + 131f * progress && py <= 382f + 136f * progress
    }
}

internal data class PanoramaLayout(
    val width: Float,
    val height: Float,
    val density: Float,
    val count: Int,
    val viewport: PanoramaRect,
    val columns: Int,
    val cellWidth: Float,
    val cellHeight: Float,
) {
    val rows: Int get() = (count + columns - 1) / columns
    val worldWidth: Float get() = columns * cellWidth
    val worldHeight: Float get() = rows.coerceAtLeast(1) * cellHeight
    val spriteSize: Float get() = cellWidth / .68f
    val caseHeight: Float get() = spriteSize * 418f / 600f

    fun center(index: Int) = PanoramaPoint(
        (index % columns + .5f) * cellWidth,
        (index / columns + .5f) * cellHeight,
    )

    fun overviewCamera(anchorIndex: Int? = null): PanoramaCamera {
        val halfView = viewport.height / 2f
        val y = if (worldHeight <= viewport.height) worldHeight / 2f else {
            val anchor = anchorIndex?.takeIf { it in 0 until count }?.let(::center)?.y ?: halfView
            anchor.coerceIn(halfView, worldHeight - halfView)
        }
        return PanoramaCamera(worldWidth / 2f, y)
    }

    fun focusCamera(index: Int, cardHeight: Float): PanoramaCamera {
        val center = center(index)
        val gap = 16f * density
        val roomForCase = (viewport.height - cardHeight - gap).coerceAtLeast(caseHeight * MIN_ZOOM)
        val zoom = min(1.4f, roomForCase / caseHeight).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val halfCase = caseHeight * zoom / 2f
        val lower = viewport.top + cardHeight + gap + halfCase
        val upper = viewport.bottom - halfCase
        val targetY = if (lower <= upper) {
            (viewport.centerY + viewport.height * .12f).coerceIn(lower, upper)
        } else upper
        return clamp(PanoramaCamera(center.x, center.y - (targetY - viewport.centerY) / zoom, zoom))
    }

    fun clamp(camera: PanoramaCamera): PanoramaCamera {
        val zoom = camera.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Keep the room in reach, while allowing an edge album to move below its action card.
        return PanoramaCamera(
            camera.x.coerceIn(0f, worldWidth),
            camera.y.coerceIn(-viewport.height * .35f / zoom, worldHeight + viewport.height * .15f / zoom),
            zoom,
        )
    }

    fun transform(camera: PanoramaCamera, centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoomChange: Float): PanoramaCamera {
        val zoom = (camera.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val worldX = camera.x + (centroidX - panX - viewport.centerX) / camera.zoom
        val worldY = camera.y + (centroidY - panY - viewport.centerY) / camera.zoom
        return clamp(PanoramaCamera(
            worldX - (centroidX - viewport.centerX) / zoom,
            worldY - (centroidY - viewport.centerY) / zoom,
            zoom,
        ))
    }

    fun placement(index: Int, camera: PanoramaCamera, lift: Float = 0f): PanoramaPlacement {
        val center = center(index)
        val size = spriteSize * camera.zoom
        return PanoramaPlacement(
            index,
            viewport.centerX + (center.x - camera.x) * camera.zoom - size * .5f,
            viewport.centerY + (center.y - camera.y) * camera.zoom - size * .515f - lift,
            size,
        )
    }

    /** One extra row/column preloads artwork without decoding the whole library. */
    fun visibleIndices(camera: PanoramaCamera, overscan: Int = 1): List<Int> {
        if (count == 0) return emptyList()
        val left = camera.x - viewport.width / (2f * camera.zoom)
        val top = camera.y - viewport.height / (2f * camera.zoom)
        val right = camera.x + viewport.width / (2f * camera.zoom)
        val bottom = camera.y + viewport.height / (2f * camera.zoom)
        val firstColumn = (floor(left / cellWidth).toInt() - overscan).coerceIn(0, columns - 1)
        val lastColumn = (ceil(right / cellWidth).toInt() + overscan).coerceIn(0, columns - 1)
        val firstRow = (floor(top / cellHeight).toInt() - overscan).coerceIn(0, rows - 1)
        val lastRow = (ceil(bottom / cellHeight).toInt() + overscan).coerceIn(0, rows - 1)
        return buildList {
            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                val index = row * columns + column
                if (index < count) add(index)
            }
        }
    }

    fun cardBounds(placement: PanoramaPlacement, cardWidth: Float, cardHeight: Float): PanoramaRect {
        val margin = 12f * density
        val safeWidth = cardWidth.coerceAtMost((width - margin * 2f).coerceAtLeast(0f))
        val safeHeight = cardHeight.coerceAtMost(viewport.height)
        val left = (placement.caseBounds.centerX - safeWidth / 2f)
            .coerceIn(margin, (width - margin - safeWidth).coerceAtLeast(margin))
        val top = (placement.caseBounds.top - 12f * density - safeHeight)
            .coerceIn(viewport.top, (viewport.bottom - safeHeight).coerceAtLeast(viewport.top))
        return PanoramaRect(left, top, left + safeWidth, top + safeHeight)
    }

    companion object {
        const val MIN_ZOOM = .65f
        const val MAX_ZOOM = 2.6f

        fun create(width: Float, height: Float, density: Float, count: Int): PanoramaLayout {
            val margin = min(20f * density, width * .05f)
            val top = min(52f * density, height * .2f)
            val bottom = maxOf(top + 1f, height - min(28f * density, height * .1f))
            val viewport = PanoramaRect(margin, top, maxOf(margin + 1f, width - margin), bottom)
            val targetWidth = (min(viewport.width, viewport.height) * .22f)
                .coerceIn(116f * density, 168f * density)
            val columns = floor(viewport.width / targetWidth).toInt()
                .coerceIn(1, min(12, count.coerceAtLeast(1)))
            val cellWidth = min(viewport.width / columns, 200f * density)
            return PanoramaLayout(width, height, density, count, viewport, columns, cellWidth, cellWidth * 1.17f)
        }
    }
}
