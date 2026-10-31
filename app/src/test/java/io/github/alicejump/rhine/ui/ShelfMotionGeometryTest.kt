package io.github.alicejump.rhine.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfMotionGeometryTest {
    private val layout = PanoramaLayout.create(1280f, 780f, 1f, 96)
    private val normal = PanoramaRect(24f, 110f, 820f, 650f)
    private val camera = layout.overviewCamera(3)

    private fun frame(progress: Float, position: Float = 3f, selectionFocus: Float = 3f) = ShelfMotionGeometry.frame(
        count = 96, normalScene = normal, panoramaLayout = layout, panoramaCamera = camera,
        position = position, selectionFocus = selectionFocus, progress = progress,
    )

    @Test
    fun bothEndpointsUseTheSameEntitiesAndTheirRespectiveLayouts() {
        val near = frame(0f)
        val full = frame(1f)
        assertEquals(near.items.map { it.index }.toSet(), full.items.map { it.index }.toSet())
        for (item in near.items) {
            val expected = ShelfMotionGeometry.normalPose(item.index, normal, 1f, 3f, 3f)
            assertEquals(expected, item)
        }
        for (item in full.items) {
            val expected = layout.placement(item.index, camera)
            assertEquals(expected.x, item.x, .002f)
            assertEquals(expected.y, item.y, .002f)
            assertEquals(expected.size, item.size, .002f)
        }
    }

    @Test
    fun halfwayMovesIndividualAlbumsInsteadOfTransformingTheWholeShelf() {
        val indices = listOf(layout.columns - 1, layout.columns, layout.columns + 1)
        val near = frame(0f, position = 0f, selectionFocus = 0f)
        val middle = frame(.5f, position = 0f, selectionFocus = 0f)
        val full = frame(1f, position = 0f, selectionFocus = 0f)
        assertTrue(indices.all { id -> middle.items.any { it.index == id } })
        // These distant neighbours begin on one shelf ray. A camera-only affine transform
        // would keep them collinear; independent album destinations already form a row turn.
        fun area(scene: ShelfMotionFrame): Float {
            val p = indices.map { id -> scene.items.first { it.index == id } }.map {
                PanoramaPoint(it.x + it.size * .5f, it.y + it.size * .515f)
            }
            return abs((p[1].x - p[0].x) * (p[2].y - p[0].y) - (p[1].y - p[0].y) * (p[2].x - p[0].x))
        }
        assertTrue(area(near) < 1f)
        assertTrue(area(middle) > 50f)
        for (id in indices) {
            val a = near.items.first { it.index == id }
            val b = middle.items.first { it.index == id }
            val c = full.items.first { it.index == id }
            assertEquals((a.x + c.x) / 2f, b.x, .002f)
            assertEquals((a.y + c.y) / 2f, b.y, .002f)
            assertEquals((a.size + c.size) / 2f, b.size, .002f)
            assertTrue(b.alpha > .1f)
        }
    }

    @Test
    fun interpolationIsReversibleIncludingRotationAndLayering() {
        val from = ShelfEntityPose(7, 10f, 90f, 400f, -8f, -2f, .2f, .8f, .45f)
        val to = ShelfEntityPose(7, 700f, 310f, 140f, 12f, 4f, 1f, 0f, 1f)
        for (p in listOf(0f, .1f, .37f, .5f, .83f, 1f)) {
            val forward = ShelfMotionGeometry.interpolate(from, to, p)
            val backward = ShelfMotionGeometry.interpolate(to, from, 1f - p)
            assertEquals(forward.index, backward.index)
            assertEquals(forward.x, backward.x, .002f)
            assertEquals(forward.y, backward.y, .002f)
            assertEquals(forward.size, backward.size, .002f)
            assertEquals(forward.rotationDegrees, backward.rotationDegrees, .002f)
            assertEquals(forward.depth, backward.depth, .002f)
            assertEquals(forward.frost, backward.frost, .002f)
        }
    }

    @Test
    fun selectionPullsTheNewCaseOutWhileTheOldCaseReturnsBeforeShelfScrolls() {
        val oldBefore = ShelfMotionGeometry.normalPose(3, normal, 1f, position = 3f, selectionFocus = 3f)
        val oldAfter = ShelfMotionGeometry.normalPose(3, normal, 1f, position = 3f, selectionFocus = 4f)
        val nextBefore = ShelfMotionGeometry.normalPose(4, normal, 1f, position = 3f, selectionFocus = 3f)
        val nextAfter = ShelfMotionGeometry.normalPose(4, normal, 1f, position = 3f, selectionFocus = 4f)
        assertTrue(oldAfter.caseBounds.centerY > oldBefore.caseBounds.centerY + 40f)
        assertTrue(nextAfter.caseBounds.centerY < nextBefore.caseBounds.centerY - 40f)
        assertTrue(nextAfter.frost < nextBefore.frost)
        assertTrue(oldAfter.frost > oldBefore.frost)
        assertTrue(nextAfter.x < nextBefore.x) // The gap beside the old selection closes.
        assertEquals(0f, nextAfter.rotationDegrees, 0f)
    }

    @Test
    fun farNeighboursStartOutsideTheNormalSceneAlongItsShelfDirection() {
        val left = ShelfMotionGeometry.normalPose(0, normal, 1f, 40f, 40f)
        val right = ShelfMotionGeometry.normalPose(95, normal, 1f, 40f, 40f)
        assertTrue(left.caseBounds.right < normal.left)
        assertTrue(right.caseBounds.left > normal.right)
        assertTrue(left.caseBounds.centerY > right.caseBounds.centerY)
        assertTrue(left.x.isFinite() && right.x.isFinite())
        assertTrue(abs(right.x) < normal.width * 4f)
    }

    @Test
    fun largeLibraryKeepsBothVisibleSetsWithoutRetainingEveryAlbum() {
        val large = PanoramaLayout.create(1280f, 780f, 1f, 100_000)
        val firstCamera = large.overviewCamera(50_000)
        val retained = ShelfMotionGeometry.transitionIndices(100_000, 5f, large, firstCamera)
        val nextCamera = large.overviewCamera(90_000)
        val next = ShelfMotionGeometry.frame(100_000, normal, large, nextCamera, 5f, 5f, .4f, retainedIndices = retained)
        val ids = next.items.map { it.index }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(retained))
        assertTrue(ids.containsAll(large.visibleIndices(nextCamera)))
        assertTrue(ids.size < 250)
    }

    @Test
    fun scrolledNormalSceneUsesItsRealCoordinatesButClipsToTheHost() {
        val scrolled = normal.copy(top = -140f, bottom = 400f)
        val scene = ShelfMotionGeometry.frame(96, scrolled, layout, camera, 3f, 3f, 0f)
        assertEquals(0f, scene.clip.top, 0f)
        assertEquals(400f, scene.clip.bottom, 0f)
        assertEquals(ShelfMotionGeometry.normalPose(3, scrolled, 1f, 3f, 3f), scene.items.first { it.index == 3 })
        assertNull(scene.hitTest(300f, -1f))
        val hidden = ShelfMotionGeometry.frame(96, scrolled.copy(bottom = -10f), layout, camera, 3f, 3f, 0f)
        assertEquals(0f, hidden.clip.height, 0f)
        assertNull(hidden.hitTest(300f, 0f))
    }

    @Test
    fun transformedHitTestingMatchesVisualGeometryAndRespectsForegroundOrder() {
        val back = ShelfEntityPose(1, 100f, 100f, 300f, rotationDegrees = 18f, depth = 0f)
        val front = back.copy(index = 2, depth = 1f)
        val center = front.caseBounds
        val scene = ShelfMotionFrame(listOf(back, front), PanoramaRect(0f, 0f, 800f, 800f), .5f)
        assertEquals(2, scene.hitTest(center.centerX, center.centerY)?.index)
        assertFalse(front.contains(front.x, front.y))
        val clipped = scene.copy(clip = PanoramaRect(0f, 0f, 80f, 80f))
        assertNull(clipped.hitTest(center.centerX, center.centerY))
        assertEquals(1, scene.copy(items = listOf(back, front.copy(alpha = 0f))).hitTest(center.centerX, center.centerY)?.index)
    }

    @Test
    fun panoramaLiftCanBeReversedAndSharedBetweenOldAndNewSelection() {
        fun lifted(amount: Float) = ShelfMotionGeometry.frame(96, normal, layout, camera,
            position = 5f, selectionFocus = 5.5f, progress = 1f, focusedIndex = 6, focusAmount = amount)
        val down = lifted(0f)
        val half = lifted(.5f)
        val up = lifted(1f)
        for (index in listOf(5, 6)) {
            val a = down.items.first { it.index == index }
            val b = half.items.first { it.index == index }
            val c = up.items.first { it.index == index }
            assertEquals(3.5f, a.y - c.y, .002f)
            assertEquals((a.y + c.y) / 2f, b.y, .002f)
        }
    }

    @Test
    fun nonAdjacentSelectionOnlyFocusesTheOldAndNewAlbums() {
        val weights = mapOf(0 to .65f, 23 to .35f)
        val close = ShelfMotionGeometry.frame(96, normal, layout, camera,
            position = 0f, selectionFocus = 23f, progress = 0f, selectionWeights = weights)
        val expanded = ShelfMotionGeometry.frame(96, normal, layout, camera,
            position = 0f, selectionFocus = 23f, progress = 1f, focusedIndex = 23, selectionWeights = weights)
        for (scene in listOf(close, expanded)) {
            assertEquals(.65f, scene.items.first { it.index == 0 }.focus, .001f)
            assertEquals(.35f, scene.items.first { it.index == 23 }.focus, .001f)
            assertTrue(scene.items.filter { it.index in 1..22 }.all { it.focus == 0f })
        }
        for (index in 1..22) {
            val unselected = ShelfMotionGeometry.normalPose(index, normal, 1f, 0f, 23f, selectionWeights = weights)
            assertEquals(0f, unselected.focus, 0f)
            assertEquals(1f, unselected.frost, 0f)
        }
        val neighbour = ShelfMotionGeometry.normalPose(1, normal, 1f, 0f, 23f, selectionWeights = weights)
        val resting = ShelfMotionGeometry.normalPose(1, normal, 1f, 0f, 23f, selectionWeights = emptyMap())
        assertTrue(neighbour.caseBounds.centerY < resting.caseBounds.centerY)
        assertEquals(resting.size, neighbour.size, 0f) // A neighbour moves aside without becoming selected.
    }

    @Test
    fun normalContentClipProtectsHeaderWithoutMovingTheSourceAlbums() {
        val scrolled = normal.copy(top = -140f, bottom = 400f)
        val content = PanoramaRect(0f, 100f, 1280f, 630f)
        val closed = ShelfMotionGeometry.frame(96, scrolled, layout, camera,
            position = 3f, selectionFocus = 3f, progress = 0f, normalClip = content)
        assertEquals(100f, closed.clip.top, 0f)
        assertEquals(400f, closed.clip.bottom, 0f)
        assertEquals(ShelfMotionGeometry.normalPose(3, scrolled, 1f, 3f, 3f), closed.items.first { it.index == 3 })
        assertNull(closed.hitTest(300f, 99f))
        val expanded = ShelfMotionGeometry.frame(96, scrolled, layout, camera,
            position = 3f, selectionFocus = 3f, progress = 1f, normalClip = content)
        assertEquals(layout.viewport.top, expanded.clip.top, .001f)
        assertEquals(layout.viewport.bottom, expanded.clip.bottom, .001f)
        val hidden = ShelfMotionGeometry.frame(96, scrolled.copy(bottom = 90f), layout, camera,
            position = 3f, selectionFocus = 3f, progress = 0f, normalClip = content)
        assertEquals(0f, hidden.clip.height, 0f)
        assertNull(hidden.hitTest(300f, 100f))
    }

    @Test
    fun emptyCollectionDoesNotCreateAHittablePlaceholderAlbum() {
        val empty = PanoramaLayout.create(390f, 700f, 1f, 0)
        val scene = ShelfMotionGeometry.frame(0, normal, empty, empty.overviewCamera(), 0f, 0f, .5f)
        assertTrue(scene.items.isEmpty())
        assertNull(scene.hitTest(195f, 350f))
    }
}
