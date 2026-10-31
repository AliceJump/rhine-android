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
    private fun point(logicalX: Float, logicalY: Float): PanoramaPoint {
        val angle = Math.toRadians(rotationDegrees.toDouble())
        val dx = (logicalX / 600f - .5f) * size
        val dy = (logicalY / 600f - .515f) * size
        return PanoramaPoint(
            x + size * .5f + dx * cos(angle).toFloat() - dy * sin(angle).toFloat(),
            y + size * .515f + dx * sin(angle).toFloat() + dy * cos(angle).toFloat(),
        )
    }

    val quad: FloatArray get() {
        if (rotationDegrees == 0f) return floatArrayOf(
            x + size * 128f / 600f, y + size * 100f / 600f,
            x + size * 472f / 600f, y + size * 231f / 600f,
            x + size * 472f / 600f, y + size * 518f / 600f,
            x + size * 128f / 600f, y + size * 382f / 600f,
        )
        val points = listOf(point(128f, 100f), point(472f, 231f), point(472f, 518f), point(128f, 382f))
        return FloatArray(8) { i -> if (i % 2 == 0) points[i / 2].x else points[i / 2].y }
    }

    val caseBounds: PanoramaRect get() = if (rotationDegrees == 0f) PanoramaRect(
        x + size * 128f / 600f, y + size * 100f / 600f,
        x + size * 472f / 600f, y + size * 518f / 600f,
    ) else bounds(listOf(point(128f, 100f), point(472f, 231f), point(472f, 518f), point(128f, 382f)))

    val accessibleBounds: PanoramaRect get() = if (rotationDegrees == 0f) PanoramaRect(
        x + size * 185f / 600f, y + size * 210f / 600f,
        x + size * 410f / 600f, y + size * 400f / 600f,
    ) else bounds(listOf(point(185f, 210f), point(410f, 210f), point(410f, 400f), point(185f, 400f)))

    fun contains(x: Float, y: Float): Boolean {
        if (size <= 0f) return false
        // Inverse the exact transform used by the painter, then test the unrotated case face.
        val angle = Math.toRadians(-rotationDegrees.toDouble())
        val dx = x - this.x - size * .5f
        val dy = y - this.y - size * .515f
        val px = (dx * cos(angle).toFloat() - dy * sin(angle).toFloat()) / size * 600f + 300f
        val py = (dx * sin(angle).toFloat() + dy * cos(angle).toFloat()) / size * 600f + 309f
        if (px !in 128f..472f) return false
        val along = (px - 128f) / 344f
        return py >= 100f + 131f * along - .001f && py <= 382f + 136f * along + .001f
    }

    /** Useful for an action card at the panorama endpoint, where rotation is zero. */
    fun asPanoramaPlacement() = PanoramaPlacement(index, x, y, size)

    private fun bounds(points: List<PanoramaPoint>) = PanoramaRect(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )
}

internal data class ShelfMotionFrame(
    /** Back-to-front order, shared by drawing and hit testing. */
    val items: List<ShelfEntityPose>,
    /** Host-local coordinates: reveal the same scene as this rectangle grows or contracts. */
    val clip: PanoramaRect,
    val progress: Float,
) {
    fun hitTest(x: Float, y: Float): ShelfEntityPose? {
        if (clip.width <= 0f || clip.height <= 0f) return null
        if (x !in clip.left..clip.right || y !in clip.top..clip.bottom) return null
        return items.asReversed().firstOrNull { it.alpha > .1f && it.contains(x, y) }
    }
}

/** Deterministic poses make interruption and reversal use the very same path, without swapping scenes. */
internal object ShelfMotionGeometry {
    private val liftSteps = floatArrayOf(.21f, .055f, .017f, -.004f, -.006f)

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
            val emphasis = selectionWeights?.get(index)?.coerceIn(0f, 1f)
                ?: if (selectionWeights == null) (1f - abs(index - selectionFocus)).coerceIn(0f, 1f) else 0f
            val lift = 7f * panoramaLayout.density * focusAmount.coerceIn(0f, 1f) * emphasis
            val full = panoramaLayout.placement(index, panoramaCamera, lift)
            val panorama = ShelfEntityPose(
                index = index, x = full.x, y = full.y, size = full.size,
                depth = index.toFloat() / count.coerceAtLeast(1) * .8f + emphasis * 2f,
                focus = emphasis,
            )
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
        return ShelfMotionFrame(items, clip, p)
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
        if (selectionWeights == null) add(selectionFocus.roundToInt().coerceIn(0, count - 1))
        else addAll(selectionWeights.filter { (index, weight) -> index in 0 until count && weight > .001f }.keys)
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
        val sprite = min(scene.width.coerceAtLeast(1f) * .96f, scene.height.coerceAtLeast(1f) * 1.08f)
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
            depth = focus * 4f - min(visibleDistance, 8f) * .1f,
            focus = focus, frost = 1f - focus, alpha = alpha,
        )
    }

    private data class SelectionInfluence(val focus: Float, val lift: Float, val gap: Float)

    private fun selectionInfluence(index: Int, selectionFocus: Float, weights: Map<Int, Float>?): SelectionInfluence {
        if (weights == null) {
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
