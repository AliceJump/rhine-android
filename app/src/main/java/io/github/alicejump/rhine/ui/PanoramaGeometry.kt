package io.github.alicejump.rhine.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Camera coordinates are in the projected room, before the viewport transform. */
internal data class PanoramaCamera(val x: Float, val y: Float, val zoom: Float = 1f)

internal data class PanoramaPoint(val x: Float, val y: Float)

internal data class PanoramaRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun intersects(other: PanoramaRect) = right > other.left && left < other.right &&
        bottom > other.top && top < other.bottom
}

/** The bundled MARBLE array sprite and its floor projection use the same camera. */
internal data class ShelfProjection(
    val x: PanoramaPoint = PanoramaPoint(79.31f, 36.9f),
    val y: PanoramaPoint = PanoramaPoint(57.56f, -50.85f),
    val z: PanoramaPoint = PanoramaPoint(0f, -75.21f),
    val origin: PanoramaPoint = PanoramaPoint(300f, 431.62f),
    val logicalSize: Float = 600f,
) {
    fun project(across: Float, along: Float, lift: Float = 0f) = PanoramaPoint(
        x.x * across + y.x * along + z.x * lift,
        x.y * across + y.y * along + z.y * lift,
    )
}

/** Includes the exposed top edge: in a dense rack that is often the only visible target. */
internal object MarbleCaseGeometry {
    val outline = floatArrayOf(128f, 108f, 155f, 84f, 472f, 231f, 472f, 499f, 446f, 518f, 128f, 382f)
    const val LEFT = 128f
    const val TOP = 84f
    const val RIGHT = 472f
    const val BOTTOM = 518f

    fun contains(polygon: FloatArray, x: Float, y: Float): Boolean {
        var positive = false
        var negative = false
        val count = polygon.size / 2
        for (i in 0 until count) {
            val next = (i + 1) % count
            val cross = (polygon[next * 2] - polygon[i * 2]) * (y - polygon[i * 2 + 1]) -
                (polygon[next * 2 + 1] - polygon[i * 2 + 1]) * (x - polygon[i * 2])
            positive = positive || cross > .001f
            negative = negative || cross < -.001f
            if (positive && negative) return false
        }
        return true
    }

    /** Separating-axis test, so a narrow foreground spine cannot pass between sample points. */
    fun intersects(polygon: FloatArray, rect: PanoramaRect): Boolean {
        if (polygon.isEmpty() || rect.width <= 0f || rect.height <= 0f) return false
        val count = polygon.size / 2
        fun separated(ax: Float, ay: Float): Boolean {
            var low = Float.POSITIVE_INFINITY
            var high = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                val dot = polygon[i * 2] * ax + polygon[i * 2 + 1] * ay
                low = min(low, dot)
                high = max(high, dot)
            }
            val center = rect.centerX * ax + rect.centerY * ay
            val radius = rect.width * .5f * abs(ax) + rect.height * .5f * abs(ay)
            return high <= center - radius || low >= center + radius
        }
        if (separated(1f, 0f) || separated(0f, 1f)) return false
        for (i in 0 until count) {
            val next = (i + 1) % count
            val dx = polygon[next * 2] - polygon[i * 2]
            val dy = polygon[next * 2 + 1] - polygon[i * 2 + 1]
            if (separated(-dy, dx)) return false
        }
        return true
    }
}

internal data class PanoramaPlacement(val index: Int, val x: Float, val y: Float, val size: Float) {
    val accessibleBounds: PanoramaRect get() = PanoramaRect(
        x + size * 185f / 600f, y + size * 210f / 600f,
        x + size * 410f / 600f, y + size * 400f / 600f,
    )
    val caseBounds: PanoramaRect get() = PanoramaRect(
        x + size * MarbleCaseGeometry.LEFT / 600f, y + size * MarbleCaseGeometry.TOP / 600f,
        x + size * MarbleCaseGeometry.RIGHT / 600f, y + size * MarbleCaseGeometry.BOTTOM / 600f,
    )
    fun contains(x: Float, y: Float): Boolean = size > 0f && MarbleCaseGeometry.contains(
        MarbleCaseGeometry.outline, (x - this.x) / size * 600f, (y - this.y) / size * 600f,
    )
}

/** World addresses are independent of orientation. Only the window and camera change. */
internal data class PanoramaLayout(
    val width: Float,
    val height: Float,
    val density: Float,
    val count: Int,
    val viewport: PanoramaRect,
    val projection: ShelfProjection = ShelfProjection(),
) {
    val slotsPerLane: Int get() = SLOTS_PER_LANE
    val columns: Int = (count.coerceAtLeast(1) - 1) / SLOTS_PER_LANE + 1
    val rows: Int = min(count, SLOTS_PER_LANE)
    val spriteSize: Float = 360f * density
    // A large tablet's normal shelf can exceed the fixed base zoom ceiling. Keep its
    // physical close-up scale reachable before the UI considers returning to that shelf.
    val maxZoom: Float = max(MAX_ZOOM,
        ShelfMotionGeometry.normalSpriteSize(PanoramaRect(0f, 0f, width, height)) / spriteSize * 1.05f)
    private val unitScale: Float = spriteSize / projection.logicalSize
    val laneStep: PanoramaPoint = projection.project(LANE_PITCH, 0f).scaled(unitScale)
    val slotStep: PanoramaPoint = projection.project(0f, SLOT_PITCH).scaled(unitScale)
    val cellWidth: Float = hypot(laneStep.x, laneStep.y)
    val cellHeight: Float = hypot(slotStep.x, slotStep.y)
    val caseHeight: Float = spriteSize * (MarbleCaseGeometry.BOTTOM - MarbleCaseGeometry.TOP) / 600f
    private val groundOrigin: PanoramaPoint = PanoramaPoint(
        spriteSize * .5f, spriteSize * .75f + (SLOTS_PER_LANE - 1) * abs(slotStep.y),
    )

    fun laneOf(index: Int): Int = index / SLOTS_PER_LANE
    fun slotOf(index: Int): Int = index % SLOTS_PER_LANE
    fun slotsInLane(lane: Int): Int = (count - lane * SLOTS_PER_LANE).coerceIn(0, SLOTS_PER_LANE)

    fun groundPoint(lane: Int, slot: Float, across: Float = 0f, lift: Float = 0f): PanoramaPoint {
        val offset = projection.project(lane * LANE_PITCH + across, slot * SLOT_PITCH, lift).scaled(unitScale)
        return PanoramaPoint(groundOrigin.x + offset.x, groundOrigin.y + offset.y)
    }

    fun worldPlacement(index: Int, gapSlots: Float = 0f, liftUnits: Float = 0f, emphasis: Float = 0f): PanoramaPlacement {
        val ground = groundPoint(laneOf(index), slotOf(index) + gapSlots, lift = liftUnits)
        val size = spriteSize * (1f + .025f * emphasis.coerceIn(0f, 1f))
        return PanoramaPlacement(index, ground.x - projection.origin.x / projection.logicalSize * size,
            ground.y - projection.origin.y / projection.logicalSize * size, size)
    }

    fun center(index: Int): PanoramaPoint = worldPlacement(index).caseBounds.let { PanoramaPoint(it.centerX, it.centerY) }

    val worldBounds: PanoramaRect = computeWorldBounds()
    private fun computeWorldBounds(): PanoramaRect {
        if (count <= 0) return worldPlacement(0).caseBounds
        val lastLane = laneOf(count - 1)
        // All extrema occur at lane ends; no scan of a large library is necessary.
        val ends = setOf(0, min(count - 1, SLOTS_PER_LANE - 1), count - 1,
            lastLane * SLOTS_PER_LANE, (lastLane * SLOTS_PER_LANE - 1).coerceAtLeast(0))
        val bounds = ends.map { worldPlacement(it).caseBounds }
        return PanoramaRect(bounds.minOf { it.left }, bounds.minOf { it.top },
            bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
    }
    val worldWidth: Float get() = worldBounds.width
    val worldHeight: Float get() = worldBounds.height

    fun overviewCamera(anchorIndex: Int? = null): PanoramaCamera {
        val bounds = worldBounds
        val fit = min(viewport.width / bounds.width.coerceAtLeast(1f), viewport.height / bounds.height.coerceAtLeast(1f)) * .94f
        // A room overview remains browsable. A pinch may go further out, down to MIN_ZOOM.
        val zoom = fit.coerceIn(.75f, 1f)
        val anchor = anchorIndex?.takeIf { it in 0 until count }?.let(::center)
            ?: PanoramaPoint(bounds.centerX, bounds.centerY)
        fun composeAxis(preferred: Float, start: Float, end: Float, visible: Float): Float {
            if (end - start <= visible) return (start + end) / 2f
            val half = visible / 2f
            return preferred.coerceIn(start + half, end - half)
        }
        // The overview frames the room on every axis that fits. A browsing anchor only
        // chooses the portion of a larger room; it cannot push its edge into the center
        // and expose an avoidable half-screen of empty floor outside the collection.
        return PanoramaCamera(
            composeAxis(anchor.x, bounds.left, bounds.right, viewport.width / zoom),
            composeAxis(anchor.y, bounds.top, bounds.bottom, viewport.height / zoom),
            zoom,
        )
    }

    fun focusCamera(index: Int, cardHeight: Float, zoomOverride: Float? = null): PanoramaCamera {
        val item = worldPlacement(index, liftUnits = SELECTED_LIFT, emphasis = 1f).caseBounds
        val gap = 14f * density
        val room = (viewport.height - cardHeight - gap).coerceAtLeast(1f)
        val zoom = min(zoomOverride ?: 1.4f, room / item.height).coerceIn(MIN_ZOOM, maxZoom)
        val halfCase = item.height * zoom / 2f
        val lower = viewport.top + cardHeight + gap + halfCase
        val upper = viewport.bottom - halfCase
        val targetY = if (lower <= upper) (viewport.centerY + viewport.height * .12f).coerceIn(lower, upper) else upper
        return clamp(PanoramaCamera(item.centerX, item.centerY - (targetY - viewport.centerY) / zoom, zoom))
    }

    fun selectedPlacement(index: Int, camera: PanoramaCamera): PanoramaPlacement = projectPlacement(
        worldPlacement(index, liftUnits = SELECTED_LIFT, emphasis = 1f), camera,
    )

    /** Restrict the camera to the actual diagonal rack, not its mostly empty bounding box. */
    fun clamp(camera: PanoramaCamera): PanoramaCamera {
        val zoom = camera.zoom.coerceIn(MIN_ZOOM, maxZoom)
        val address = inverse(PanoramaPoint(camera.x, camera.y))
        val lanePadding = viewport.width / (laneStep.x.coerceAtLeast(1f) * zoom) * .35f + 1f
        val slotPadding = viewport.height / (abs(slotStep.y).coerceAtLeast(1f) * zoom) * .55f + 1f
        val lane = address.x.coerceIn(-lanePadding, columns - 1f + lanePadding)
        val slot = address.y.coerceIn(-slotPadding, (rows - 1).coerceAtLeast(0) + slotPadding)
        val first = center(0)
        return PanoramaCamera(first.x + laneStep.x * lane + slotStep.x * slot,
            first.y + laneStep.y * lane + slotStep.y * slot, zoom)
    }

    fun transform(camera: PanoramaCamera, centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoomChange: Float): PanoramaCamera {
        val zoom = (camera.zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
        val worldX = camera.x + (centroidX - panX - viewport.centerX) / camera.zoom
        val worldY = camera.y + (centroidY - panY - viewport.centerY) / camera.zoom
        return clamp(PanoramaCamera(worldX - (centroidX - viewport.centerX) / zoom,
            worldY - (centroidY - viewport.centerY) / zoom, zoom))
    }

    fun projectPoint(point: PanoramaPoint, camera: PanoramaCamera) = PanoramaPoint(
        viewport.centerX + (point.x - camera.x) * camera.zoom,
        viewport.centerY + (point.y - camera.y) * camera.zoom,
    )

    fun projectPlacement(world: PanoramaPlacement, camera: PanoramaCamera): PanoramaPlacement {
        val origin = projectPoint(PanoramaPoint(world.x, world.y), camera)
        return PanoramaPlacement(world.index, origin.x, origin.y, world.size * camera.zoom)
    }

    /** Legacy lift argument is in screen pixels; shared selection uses worldPlacement instead. */
    fun placement(index: Int, camera: PanoramaCamera, lift: Float = 0f): PanoramaPlacement =
        projectPlacement(worldPlacement(index), camera).let { it.copy(y = it.y - lift) }

    fun caseScaleRelativeTo(normalScene: PanoramaRect, camera: PanoramaCamera): Float =
        spriteSize * camera.zoom / ShelfMotionGeometry.normalSpriteSize(normalScene)

    fun nearestIndex(camera: PanoramaCamera): Int? = nearestIndex(PanoramaPoint(camera.x, camera.y))

    fun nearestIndex(point: PanoramaPoint): Int? {
        if (count <= 0) return null
        var best: Int? = null
        var distance = Float.POSITIVE_INFINITY
        // Eight parallel slot lines suffice, regardless of how many lanes the library has.
        for (slot in 0 until rows) {
            val first = center(slot)
            val dot = (point.x - first.x) * laneStep.x + (point.y - first.y) * laneStep.y
            val lane = (dot / (laneStep.x * laneStep.x + laneStep.y * laneStep.y)).roundToInt()
                .coerceIn(0, (count - 1 - slot) / SLOTS_PER_LANE)
            val index = lane * SLOTS_PER_LANE + slot
            val center = center(index)
            val squared = (point.x - center.x) * (point.x - center.x) + (point.y - center.y) * (point.y - center.y)
            if (squared < distance) { best = index; distance = squared }
        }
        return best
    }

    /** Inverse projection limits both candidate lanes and slots, even with 100,000 albums. */
    fun visibleIndices(camera: PanoramaCamera, overscan: Int = 1): List<Int> {
        if (count == 0) return emptyList()
        val margin = spriteSize * (.62f + overscan.coerceAtLeast(0) * .12f)
        val left = camera.x - viewport.width / (2f * camera.zoom) - margin
        val top = camera.y - viewport.height / (2f * camera.zoom) - margin
        val right = camera.x + viewport.width / (2f * camera.zoom) + margin
        val bottom = camera.y + viewport.height / (2f * camera.zoom) + margin
        val corners = listOf(PanoramaPoint(left, top), PanoramaPoint(right, top),
            PanoramaPoint(right, bottom), PanoramaPoint(left, bottom)).map(::inverse)
        val firstLane = floor(corners.minOf { it.x }).toInt().coerceIn(0, columns - 1)
        val lastLane = ceil(corners.maxOf { it.x }).toInt().coerceIn(0, columns - 1)
        val firstSlot = floor(corners.minOf { it.y }).toInt().coerceIn(0, rows - 1)
        val lastSlot = ceil(corners.maxOf { it.y }).toInt().coerceIn(0, rows - 1)
        val window = PanoramaRect(left, top, right, bottom)
        return buildList {
            for (lane in firstLane..lastLane) for (slot in firstSlot..lastSlot) {
                val index = lane * SLOTS_PER_LANE + slot
                if (index < count && worldPlacement(index).caseBounds.intersects(window)) add(index)
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

    private fun inverse(point: PanoramaPoint): PanoramaPoint {
        val first = center(0)
        val dx = point.x - first.x
        val dy = point.y - first.y
        val lane = laneStep
        val slot = slotStep
        val determinant = lane.x * slot.y - lane.y * slot.x
        return PanoramaPoint((dx * slot.y - dy * slot.x) / determinant,
            (lane.x * dy - lane.y * dx) / determinant)
    }

    companion object {
        const val MIN_ZOOM = .3f
        const val MAX_ZOOM = 2.6f
        const val SLOTS_PER_LANE = 8
        const val LANE_PITCH = 4.65f
        const val SLOT_PITCH = .74f
        const val SELECTED_LIFT = 1.7f

        fun create(width: Float, height: Float, density: Float, count: Int,
            projection: ShelfProjection = ShelfProjection()): PanoramaLayout {
            val margin = min(20f * density, width * .05f)
            val top = min(52f * density, height * .2f)
            val bottom = maxOf(top + 1f, height - min(28f * density, height * .1f))
            val viewport = PanoramaRect(margin, top, maxOf(margin + 1f, width - margin), bottom)
            return PanoramaLayout(width, height, density, count, viewport, projection)
        }
    }
}

private fun PanoramaPoint.scaled(scale: Float) = PanoramaPoint(x * scale, y * scale)
