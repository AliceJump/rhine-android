package io.github.alicejump.rhine.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One album has one pose throughout both layouts; x/y are sprite origins in host coordinates. */
internal data class ShelfEntityPose(
    val index: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val rotationDegrees: Float = 0f,
    val depth: Float = 0f,
    val focus: Float = 0f,
    val frost: Float = 0f,
    val alpha: Float = 1f,
) {
    fun point(logicalX: Float, logicalY: Float): PanoramaPoint {
        if (rotationDegrees == 0f) return PanoramaPoint(x + logicalX / 600f * size, y + logicalY / 600f * size)
        val angle = Math.toRadians(rotationDegrees.toDouble())
        val dx = (logicalX / 600f - .5f) * size
        val dy = (logicalY / 600f - .515f) * size
        return PanoramaPoint(
            x + size * .5f + dx * cos(angle).toFloat() - dy * sin(angle).toFloat(),
            y + size * .515f + dx * sin(angle).toFloat() + dy * cos(angle).toFloat(),
        )
    }

    val quad: FloatArray get() {
        val outline = MarbleCaseGeometry.outline
        if (rotationDegrees == 0f) return FloatArray(outline.size) { i ->
            (if (i % 2 == 0) x else y) + size * outline[i] / 600f
        }
        val points = (outline.indices step 2).map { point(outline[it], outline[it + 1]) }
        return FloatArray(outline.size) { i -> if (i % 2 == 0) points[i / 2].x else points[i / 2].y }
    }

    val caseBounds: PanoramaRect get() = if (rotationDegrees == 0f) PanoramaRect(
        x + size * MarbleCaseGeometry.LEFT / 600f, y + size * MarbleCaseGeometry.TOP / 600f,
        x + size * MarbleCaseGeometry.RIGHT / 600f, y + size * MarbleCaseGeometry.BOTTOM / 600f,
    ) else bounds((MarbleCaseGeometry.outline.indices step 2).map {
        point(MarbleCaseGeometry.outline[it], MarbleCaseGeometry.outline[it + 1])
    })

    val accessibleBounds: PanoramaRect get() = if (rotationDegrees == 0f) PanoramaRect(
        x + size * 185f / 600f, y + size * 210f / 600f,
        x + size * 410f / 600f, y + size * 400f / 600f,
    ) else bounds(listOf(point(185f, 210f), point(410f, 210f), point(410f, 400f), point(185f, 400f)))

    fun contains(x: Float, y: Float): Boolean {
        if (size <= 0f) return false
        if (rotationDegrees == 0f) return MarbleCaseGeometry.contains(MarbleCaseGeometry.outline,
            (x - this.x) / size * 600f, (y - this.y) / size * 600f)
        // Inverse the exact transform used by the painter, then test the unrotated case face.
        val angle = Math.toRadians(-rotationDegrees.toDouble())
        val dx = x - this.x - size * .5f
        val dy = y - this.y - size * .515f
        val px = (dx * cos(angle).toFloat() - dy * sin(angle).toFloat()) / size * 600f + 300f
        val py = (dx * sin(angle).toFloat() + dy * cos(angle).toFloat()) / size * 600f + 309f
        return MarbleCaseGeometry.contains(MarbleCaseGeometry.outline, px, py)
    }

    /** Useful for an action card at the panorama endpoint, where rotation is zero. */
    fun asPanoramaPlacement() = PanoramaPlacement(index, x, y, size)

    private fun bounds(points: List<PanoramaPoint>) = PanoramaRect(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )
}

internal data class ShelfRailPose(
    val lane: Int,
    val floor: FloatArray,
    val frontLip: FloatArray,
    val dividers: List<FloatArray>,
    val depth: Float,
    val alpha: Float,
)

internal data class ShelfMotionFrame(
    /** Back-to-front order, shared by drawing and hit testing. */
    val items: List<ShelfEntityPose>,
    /** Host-local coordinates: reveal the same scene as this rectangle grows or contracts. */
    val clip: PanoramaRect,
    val progress: Float,
    val rails: List<ShelfRailPose> = emptyList(),
) {
    private data class Shape(val item: ShelfEntityPose, val bounds: PanoramaRect, val polygon: FloatArray)
    private data class LipShape(val depth: Float, val bounds: PanoramaRect, val polygon: FloatArray)
    private val visibleCache = mutableMapOf<Int, PanoramaRect?>()
    private val shapes by lazy(LazyThreadSafetyMode.NONE) {
        items.filter { it.alpha > .1f && it.caseBounds.intersects(clip) }
            .map { Shape(it, it.caseBounds, it.quad) }
    }
    private val shapeByIndex by lazy(LazyThreadSafetyMode.NONE) { shapes.associateBy { it.item.index } }
    private val lips by lazy(LazyThreadSafetyMode.NONE) {
        rails.filter { it.alpha > .1f }.map { rail ->
            val points = rail.frontLip
            val bounds = PanoramaRect(
                (points.indices step 2).minOf { points[it] },
                (points.indices step 2).minOf { points[it + 1] },
                (points.indices step 2).maxOf { points[it] },
                (points.indices step 2).maxOf { points[it + 1] },
            )
            LipShape(rail.depth, bounds, points)
        }.filter { it.bounds.intersects(clip) }
    }
    private val occluders by lazy(LazyThreadSafetyMode.NONE) {
        buildMap {
            for (back in shapes.indices) {
                val shape = shapes[back]
                put(shape.item.index, buildList {
                    for (front in back + 1 until shapes.size) {
                        val other = shapes[front]
                        if (other.bounds.intersects(shape.bounds)) add(other.polygon)
                    }
                    // drawFrame paints a lip after all cases at the same depth. It is
                    // opaque scenery, so an album target cannot extend underneath it.
                    for (lip in lips) {
                        if (lip.depth >= shape.item.depth && lip.bounds.intersects(shape.bounds)) add(lip.polygon)
                    }
                })
            }
        }
    }

    fun hitTest(x: Float, y: Float): ShelfEntityPose? {
        if (clip.width <= 0f || clip.height <= 0f) return null
        if (x !in clip.left..clip.right || y !in clip.top..clip.bottom) return null
        val front = shapes.asReversed().firstOrNull {
            it.bounds.contains(x, y) && MarbleCaseGeometry.contains(it.polygon, x, y)
        } ?: return null
        // Foreground rails return background rather than selecting a case hidden by the lip.
        if (lips.any { it.depth >= front.item.depth && it.bounds.contains(x, y) &&
                MarbleCaseGeometry.contains(it.polygon, x, y) }) return null
        return front.item
    }

    /** A real exposed piece of this case; its center cannot click a foreground album. */
    fun visibleBounds(index: Int): PanoramaRect? {
        if (visibleCache.containsKey(index)) return visibleCache[index]
        val shape = shapeByIndex[index] ?: return null
        val item = shape.item
        val foreground = occluders[index].orEmpty()
        fun exposed(rect: PanoramaRect): Boolean {
            if (rect.width <= 0f || rect.height <= 0f || rect.left < clip.left || rect.right > clip.right ||
                rect.top < clip.top || rect.bottom > clip.bottom) return false
            if (!MarbleCaseGeometry.contains(shape.polygon, rect.left, rect.top) ||
                !MarbleCaseGeometry.contains(shape.polygon, rect.right, rect.top) ||
                !MarbleCaseGeometry.contains(shape.polygon, rect.right, rect.bottom) ||
                !MarbleCaseGeometry.contains(shape.polygon, rect.left, rect.bottom)) return false
            return foreground.none { MarbleCaseGeometry.intersects(it, rect) }
        }
        if (exposed(item.accessibleBounds)) return item.accessibleBounds.also { visibleCache[index] = it }

        // Search the roof first, then any cover revealed by an extraction. Rectangles stay
        // inside the exact polygon and outside every occluder, rather than using its AABB.
        val candidates = buildList {
            for (x in listOf(195f, 245f, 295f, 345f, 395f, 430f)) add(item.point(x, 113f + (x - 155f) * .464f))
            for (y in listOf(185f, 245f, 310f, 370f)) for (x in listOf(220f, 300f, 380f)) add(item.point(x, y))
        }
        val sizes = listOf(.28f to .20f, .20f to .10f, .13f to .055f, .10f to .045f, .09f to .032f, .05f to .018f)
        for ((width, height) in sizes) for (point in candidates) {
            val rect = PanoramaRect(point.x - item.size * width / 2f, point.y - item.size * height / 2f,
                point.x + item.size * width / 2f, point.y + item.size * height / 2f)
            if (exposed(rect)) return rect.also { visibleCache[index] = it }
        }
        visibleCache[index] = null
        return null
    }

    /** A diagnostic / accessibility background target verified against the actual scene. */
    fun backgroundPoint(): PanoramaPoint? {
        if (clip.width < 24f || clip.height < 24f) return null
        val polygons = shapes.map { it.polygon }
        val inset = min(20f, min(clip.width, clip.height) * .1f)
        for (row in 0..6) for (column in 0..8) {
            val x = clip.left + inset + (clip.width - 2f * inset) * column / 8f
            val y = clip.top + inset + (clip.height - 2f * inset) * row / 6f
            val target = PanoramaRect(x - 6f, y - 6f, x + 6f, y + 6f)
            if (polygons.none { MarbleCaseGeometry.intersects(it, target) }) return PanoramaPoint(x, y)
        }
        return null
    }
}

/** Deterministic poses make interruption and reversal use the very same path, without swapping scenes. */
internal object ShelfMotionGeometry {
    private val liftSteps = floatArrayOf(.21f, .055f, .017f, -.004f, -.006f)

    @Suppress("UNUSED_PARAMETER") // Kept for callers while selection, cards and camera are separated.
    fun frame(
        count: Int,
        normalScene: PanoramaRect,
        panoramaLayout: PanoramaLayout,
        panoramaCamera: PanoramaCamera,
        position: Float,
        selectionFocus: Float,
        progress: Float,
        dragOffset: Float = 0f,
        focusedIndex: Int = -1,
        retainedIndices: Set<Int> = emptySet(),
        focusAmount: Float = if (focusedIndex >= 0) 1f else 0f,
        selectionWeights: Map<Int, Float>? = null,
        normalClip: PanoramaRect? = null,
    ): ShelfMotionFrame {
        val p = progress.coerceIn(0f, 1f)
        val indices = transitionIndices(count, position, panoramaLayout, panoramaCamera, selectionFocus, selectionWeights)
            .plus(retainedIndices).filter { it in 0 until count }
        val items = indices.map { index ->
            val normal = normalPose(index, normalScene, panoramaLayout.density, position, selectionFocus, dragOffset, selectionWeights)
            val panorama = panoramaPose(index, panoramaLayout, panoramaCamera, selectionFocus, selectionWeights)
            interpolate(normal, panorama, p)
        }.sortedWith(compareBy<ShelfEntityPose> { it.depth }.thenBy { it.index })
        val panoramaClip = PanoramaRect(0f, panoramaLayout.viewport.top, panoramaLayout.width, panoramaLayout.viewport.bottom)
        val normalVisible = normalClip?.let { intersect(normalScene, it) } ?: normalScene
        val scene = interpolate(normalVisible, panoramaClip, p)
        // Scrolling can place the normal scene partly or wholly above the persistent host.
        // Keep those source coordinates for motion, but never draw/hit outside the host.
        val left = scene.left.coerceIn(0f, panoramaLayout.width)
        val top = scene.top.coerceIn(0f, panoramaLayout.height)
        val clip = PanoramaRect(
            left, top, scene.right.coerceIn(left, panoramaLayout.width),
            scene.bottom.coerceIn(top, panoramaLayout.height),
        )
        val railAlpha = smooth(((p - .35f) / .65f).coerceIn(0f, 1f))
        val rails = if (railAlpha <= 0f) emptyList() else indices.map(panoramaLayout::laneOf).distinct().map { lane ->
            railPose(lane, panoramaLayout, panoramaCamera, railAlpha)
        }.sortedBy { it.depth }
        return ShelfMotionFrame(items, clip, p, rails)
    }

    /** Same shared weights as normalPose; card visibility never changes physical selection. */
    fun panoramaPose(index: Int, layout: PanoramaLayout, camera: PanoramaCamera,
        selectionFocus: Float, selectionWeights: Map<Int, Float>? = null): ShelfEntityPose {
        val weights = selectionWeights ?: legacyWeights(selectionFocus)
        val lane = layout.laneOf(index)
        val ownLane = weights.filterKeys { layout.laneOf(it) == lane }
        val influence = selectionInfluence(index, selectionFocus, ownLane)
        val lift = influence.lift / liftSteps[0] * PanoramaLayout.SELECTED_LIFT
        val world = layout.worldPlacement(index, influence.gap, lift, influence.focus)
        val full = layout.projectPlacement(world, camera)
        return ShelfEntityPose(index, full.x, full.y, full.size,
            // Increasing lanes come forward; increasing slots recede. A selected case
            // emerges from its own slot instead of being painted over the whole room.
            depth = lane * (layout.slotsPerLane + 1f) - layout.slotOf(index),
            focus = influence.focus, frost = 1f - influence.focus)
    }

    fun normalSpriteSize(scene: PanoramaRect): Float =
        min(scene.width.coerceAtLeast(1f) * .96f, scene.height.coerceAtLeast(1f) * 1.08f)

    private fun railPose(lane: Int, layout: PanoramaLayout, camera: PanoramaCamera, alpha: Float): ShelfRailPose {
        val end = (layout.slotsInLane(lane) - 1).coerceAtLeast(0) + .55f
        fun point(across: Float, slot: Float, z: Float = -.1f) =
            layout.projectPoint(layout.groundPoint(lane, slot, across, z), camera)
        fun polygon(vararg points: PanoramaPoint) = FloatArray(points.size * 2) { i ->
            if (i % 2 == 0) points[i / 2].x else points[i / 2].y
        }
        val floor = polygon(point(-2.28f, -.55f), point(2.28f, -.55f), point(2.28f, end), point(-2.28f, end))
        val lip = polygon(point(-2.28f, -.55f, .02f), point(2.28f, -.55f, .02f),
            point(2.28f, -.55f, -.22f), point(-2.28f, -.55f, -.22f))
        val dividers = (0 until layout.slotsInLane(lane)).map { slot ->
            polygon(point(-2.2f, slot - .36f, -.08f), point(2.2f, slot - .36f, -.08f))
        }
        return ShelfRailPose(lane, floor, lip, dividers, lane * (layout.slotsPerLane + 1f) + .5f, alpha)
    }

    /** Retain this set at a transition's start and union it on reversal or a new target. */
    fun transitionIndices(
        count: Int,
        position: Float,
        panoramaLayout: PanoramaLayout,
        panoramaCamera: PanoramaCamera,
        selectionFocus: Float = position,
        selectionWeights: Map<Int, Float>? = null,
    ): Set<Int> = buildSet {
        if (count <= 0) return@buildSet
        val first = (floor(position).toInt() - 5).coerceAtLeast(0)
        val last = (ceil(position).toInt() + 5).coerceAtMost(count - 1)
        if (first <= last) addAll(first..last)
        if (selectionWeights == null) {
            if (selectionFocus >= 0f) add(selectionFocus.roundToInt().coerceIn(0, count - 1))
        } else addAll(selectionWeights.filter { (index, weight) -> index in 0 until count && weight > .001f }.keys)
        addAll(panoramaLayout.visibleIndices(panoramaCamera))
    }

    fun normalPose(
        index: Int,
        scene: PanoramaRect,
        density: Float,
        position: Float,
        selectionFocus: Float,
        dragOffset: Float = 0f,
        selectionWeights: Map<Int, Float>? = null,
    ): ShelfEntityPose {
        val sprite = normalSpriteSize(scene)
        val stride = sprite * .155f
        val relative = index - position
        // Selection moves before the slower shelf scroll. The chosen case comes out; its
        // neighbours lift a little, while the two sides make different amounts of room.
        val influence = selectionInfluence(index, selectionFocus, selectionWeights)
        val focus = influence.focus

        // Unloaded neighbours continue along the shelf ray, outside its viewport. Far-away
        // albums do not materialize at the selected case, nor travel millions of pixels.
        val visibleDistance = abs(relative)
        val edgeSlots = max(5f, (scene.width / 2f + sprite * .62f) / stride)
        val along = when {
            visibleDistance <= 4f -> visibleDistance
            visibleDistance < 5f -> lerp(4f, edgeSlots, smooth(visibleDistance - 4f))
            else -> edgeSlots + 3f * (1f - exp(-(visibleDistance - 5f) / 3f))
        } * if (relative < 0f) -1f else 1f
        val shelfOffset = (along + influence.gap) * stride
        val size = sprite * (1f + .025f * focus)
        val centerX = scene.centerX + shelfOffset + dragOffset
        val centerY = scene.top + scene.height * .68f - shelfOffset * .34f - influence.lift * sprite
        val edgeVisibility = smooth((5f - visibleDistance).coerceIn(0f, 1f))
        val alpha = (.6f + focus * .4f - (visibleDistance - 1f).coerceAtLeast(0f) * .055f)
            .coerceIn(.24f, 1f) * edgeVisibility
        return ShelfEntityPose(
            index = index, x = centerX - size * .5f, y = centerY - size * .515f, size = size,
            depth = -relative,
            focus = focus, frost = 1f - focus, alpha = alpha,
        )
    }

    private data class SelectionInfluence(val focus: Float, val lift: Float, val gap: Float)

    private fun legacyWeights(selectionFocus: Float): Map<Int, Float> {
        if (selectionFocus < 0f) return emptyMap()
        val lower = floor(selectionFocus).toInt()
        val part = smooth(selectionFocus - lower)
        return if (part <= 0f) mapOf(lower to 1f) else mapOf(lower to 1f - part, lower + 1 to part)
    }

    private fun selectionInfluence(index: Int, selectionFocus: Float, weights: Map<Int, Float>?): SelectionInfluence {
        if (weights == null) {
            if (selectionFocus < 0f) return SelectionInfluence(0f, 0f, 0f)
            val relative = index - selectionFocus
            val distance = abs(relative)
            val step = floor(distance).toInt().coerceAtMost(liftSteps.lastIndex)
            val lift = if (step == liftSteps.lastIndex) liftSteps.last()
            else lerp(liftSteps[step], liftSteps[step + 1], smooth(distance - step))
            val gap = (if (relative >= 0f) .55f else -.25f) * smooth(distance.coerceAtMost(1f))
            return SelectionInfluence(smooth((1f - distance).coerceIn(0f, 1f)), lift, gap)
        }
        // Each touched album owns its weight. A non-adjacent selection never sweeps focus
        // over unrelated indices; those cases only receive their neighbours' small lift/gap.
        var lift = 0f
        var gap = 0f
        for ((selectedIndex, rawWeight) in weights) {
            val weight = rawWeight.coerceIn(0f, 1f)
            if (weight <= 0f) continue
            val distance = abs(index.toLong() - selectedIndex.toLong()).coerceAtMost(liftSteps.lastIndex.toLong()).toInt()
            lift += liftSteps[distance] * weight
            gap += when {
                index > selectedIndex -> .55f * weight
                index < selectedIndex -> -.25f * weight
                else -> 0f
            }
        }
        return SelectionInfluence(weights[index]?.coerceIn(0f, 1f) ?: 0f, lift, gap)
    }

    fun interpolate(from: ShelfEntityPose, to: ShelfEntityPose, progress: Float): ShelfEntityPose {
        require(from.index == to.index) { "A transition must keep the album identity" }
        val p = progress.coerceIn(0f, 1f)
        return ShelfEntityPose(
            index = from.index,
            x = lerp(from.x, to.x, p), y = lerp(from.y, to.y, p), size = lerp(from.size, to.size, p),
            rotationDegrees = lerp(from.rotationDegrees, to.rotationDegrees, p),
            depth = lerp(from.depth, to.depth, p), focus = lerp(from.focus, to.focus, p),
            frost = lerp(from.frost, to.frost, p), alpha = lerp(from.alpha, to.alpha, p),
        )
    }

    private fun interpolate(from: PanoramaRect, to: PanoramaRect, p: Float) = PanoramaRect(
        lerp(from.left, to.left, p), lerp(from.top, to.top, p),
        lerp(from.right, to.right, p), lerp(from.bottom, to.bottom, p),
    )

    private fun intersect(a: PanoramaRect, b: PanoramaRect): PanoramaRect {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        return PanoramaRect(left, top, max(left, min(a.right, b.right)), max(top, min(a.bottom, b.bottom)))
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
    private fun lerp(from: Float, to: Float, p: Float): Float = from + (to - from) * p
}
