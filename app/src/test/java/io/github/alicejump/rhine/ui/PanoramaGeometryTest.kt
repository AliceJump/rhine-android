package io.github.alicejump.rhine.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaGeometryTest {
    @Test
    fun rotationKeepsEveryAlbumInItsWorldLaneAndSlot() {
        val portrait = PanoramaLayout.create(800f, 1150f, 1f, 24)
        val landscape = PanoramaLayout.create(1280f, 670f, 1f, 24)
        val phone = PanoramaLayout.create(390f, 700f, 1f, 24)
        for (other in listOf(landscape, phone)) {
            assertEquals(3, other.columns)
            assertEquals(8, other.rows)
            assertEquals(portrait.worldBounds, other.worldBounds)
            for (index in 0 until 24) {
                assertEquals(portrait.laneOf(index), other.laneOf(index))
                assertEquals(portrait.slotOf(index), other.slotOf(index))
                assertEquals(portrait.center(index), other.center(index))
            }
        }
        assertTrue(landscape.viewport.width > portrait.viewport.width)
        assertTrue(landscape.viewport.height < portrait.viewport.height)
    }

    @Test
    fun theSameCameraSeesMoreParallelLanesInTheWiderWindow() {
        val portrait = PanoramaLayout.create(800f, 1150f, 1f, 160)
        val landscape = PanoramaLayout.create(1280f, 670f, 1f, 160)
        val center = portrait.center(83)
        val camera = PanoramaCamera(center.x, center.y)
        fun lanes(layout: PanoramaLayout) = layout.visibleIndices(camera, 0)
            .filter { layout.placement(it, camera).caseBounds.intersects(layout.viewport) }
            .map(layout::laneOf).distinct().size
        assertTrue(lanes(landscape) > lanes(portrait))
    }

    @Test
    fun worldScaleUsesDensityButDoesNotReflowForSplitWindows() {
        val fullWidth = PanoramaLayout.create(1200f, 760f, 1f, 40)
        val splitWindow = PanoramaLayout.create(460f, 760f, 1f, 40)
        val highDensity = PanoramaLayout.create(2400f, 1520f, 2f, 40)
        assertEquals(fullWidth.columns, splitWindow.columns)
        assertEquals(fullWidth.center(17), splitWindow.center(17))
        assertEquals(fullWidth.center(17).x * 2f, highDensity.center(17).x, .01f)
        assertEquals(fullWidth.center(17).y * 2f, highDensity.center(17).y, .01f)
        assertEquals(fullWidth.spriteSize * 2f, highDensity.spriteSize, .01f)
    }

    @Test
    fun rackSlotsOverlapAlongTheMetadataAxisInsteadOfFormingSeparateCells() {
        val layout = PanoramaLayout.create(1280f, 700f, 1f, 24)
        val first = layout.worldPlacement(0)
        val second = layout.worldPlacement(1)
        assertTrue(first.caseBounds.intersects(second.caseBounds))
        assertEquals(57.56f * .74f * .6f, second.x - first.x, .002f)
        assertEquals(-50.85f * .74f * .6f, second.y - first.y, .002f)
        val nextLane = layout.worldPlacement(8)
        assertEquals(79.31f * 4.65f * .6f, nextLane.x - first.x, .002f)
        assertEquals(36.9f * 4.65f * .6f, nextLane.y - first.y, .002f)
    }

    @Test
    fun restingFaceBoundsStayInsideTheActualCaseIncludingItsTopSpine() {
        val layout = PanoramaLayout.create(390f, 700f, 1f, 24)
        val item = layout.placement(4, layout.overviewCamera(4))
        val bounds = item.accessibleBounds
        for (x in listOf(bounds.left, bounds.centerX, bounds.right)) {
            for (y in listOf(bounds.top, bounds.centerY, bounds.bottom)) assertTrue(item.contains(x, y))
        }
        assertTrue(item.contains(item.x + item.size * 245f / 600f, item.y + item.size * 143f / 600f))
        assertFalse(item.contains(item.x, item.y))
    }

    @Test
    fun smallCollectionOverviewCentersTheWholeRoomEvenWithAnEdgeAnchor() {
        val layout = PanoramaLayout.create(1280f, 780f, 1f, 24)
        for (index in listOf(0, 7, 16, 23)) {
            val camera = layout.overviewCamera(index)
            assertEquals(layout.worldBounds.centerX, camera.x, .002f)
            assertEquals(layout.worldBounds.centerY, camera.y, .002f)
            for (album in 0 until 24) {
                val bounds = layout.placement(album, camera).caseBounds
                assertTrue(bounds.left >= layout.viewport.left - .01f)
                assertTrue(bounds.right <= layout.viewport.right + .01f)
                assertTrue(bounds.top >= layout.viewport.top - .01f)
                assertTrue(bounds.bottom <= layout.viewport.bottom + .01f)
            }
        }
    }

    @Test
    fun largeCollectionKeepsMiddleOverviewAnchorsButStopsAtContentEdges() {
        val layout = PanoramaLayout.create(1280f, 780f, 1f, 100_000)
        val middle = layout.overviewCamera(50_003)
        assertEquals(layout.center(50_003).x, middle.x, .002f)
        assertEquals(layout.center(50_003).y, middle.y, .002f)
        assertEquals(50_003, layout.nearestIndex(middle))
        for (index in listOf(0, 99_999)) {
            val camera = layout.overviewCamera(index)
            val halfWidth = layout.viewport.width / (2f * camera.zoom)
            val halfHeight = layout.viewport.height / (2f * camera.zoom)
            assertTrue(camera.x - halfWidth >= layout.worldBounds.left - .25f)
            assertTrue(camera.x + halfWidth <= layout.worldBounds.right + .25f)
            assertTrue(camera.y - halfHeight >= layout.worldBounds.top - .25f)
            assertTrue(camera.y + halfHeight <= layout.worldBounds.bottom + .25f)
        }
    }

    @Test
    fun narrowOverviewCentersTheAxisThatFitsAndBoundsTheOtherOne() {
        val layout = PanoramaLayout.create(411f, 700f, 1f, 24)
        for (index in listOf(0, 23)) {
            val camera = layout.overviewCamera(index)
            assertEquals(layout.worldBounds.centerY, camera.y, .002f)
            val halfWidth = layout.viewport.width / (2f * camera.zoom)
            assertTrue(camera.x - halfWidth >= layout.worldBounds.left - .01f)
            assertTrue(camera.x + halfWidth <= layout.worldBounds.right + .01f)
        }
    }

    @Test
    fun edgeSelectionsLeaveRoomAboveTheRaisedCaseForTheActionCard() {
        for ((width, height) in listOf(390f to 700f, 800f to 1150f, 1280f to 670f)) {
            val layout = PanoramaLayout.create(width, height, 1f, 120)
            for (index in listOf(0, 5, 119)) {
                val camera = layout.focusCamera(index, 112f)
                val item = layout.selectedPlacement(index, camera)
                val card = layout.cardBounds(item, minOf(272f, width - 24f), 112f)
                assertTrue(card.left >= 0f)
                assertTrue(card.right <= width)
                assertTrue(card.top >= layout.viewport.top)
                assertTrue(card.bottom <= item.caseBounds.top + .01f)
                assertTrue(item.caseBounds.bottom <= layout.viewport.bottom + .01f)
                assertTrue(layout.visibleIndices(camera).contains(index))
            }
        }
    }

    @Test
    fun focusRespectsTheScaleOverrideAndShortWindowsContainTheWholeCard() {
        val layout = PanoramaLayout.create(720f, 240f, 1f, 24)
        val cardHeight = layout.viewport.height * .55f
        val camera = layout.focusCamera(23, cardHeight, zoomOverride = .6f)
        val card = layout.cardBounds(layout.selectedPlacement(23, camera), 272f, cardHeight)
        assertTrue(camera.zoom <= .6f)
        assertTrue(card.left >= 0f && card.top >= 0f)
        assertTrue(card.right <= layout.width && card.bottom <= layout.height)
        assertEquals(cardHeight, card.height, .01f)
        val tall = PanoramaLayout.create(800f, 1150f, 1f, 24)
        assertEquals(.8f, tall.focusCamera(4, 112f, .8f).zoom, .001f)
    }

    @Test
    fun zoomKeepsTheContentUnderThePinchCentroidInPlace() {
        val layout = PanoramaLayout.create(800f, 1000f, 1f, 160)
        val center = layout.center(83)
        val before = PanoramaCamera(center.x, center.y, 1.2f)
        val x = 450f
        val y = 530f
        val after = layout.transform(before, x, y, 0f, 0f, 1.25f)
        assertEquals(before.x + (x - layout.viewport.centerX) / before.zoom,
            after.x + (x - layout.viewport.centerX) / after.zoom, .01f)
        assertEquals(before.y + (y - layout.viewport.centerY) / before.zoom,
            after.y + (y - layout.viewport.centerY) / after.zoom, .01f)
    }

    @Test
    fun dragAndZoomAreBoundedAndLargeLibrariesAreCulled() {
        val layout = PanoramaLayout.create(1280f, 720f, 1f, 100_000)
        val zoomedOut = layout.transform(layout.overviewCamera(), 640f, 360f, 1_000_000f, 1_000_000f, .001f)
        assertEquals(PanoramaLayout.MIN_ZOOM, zoomedOut.zoom, 0f)
        assertTrue(zoomedOut.x.isFinite() && zoomedOut.y.isFinite())
        assertTrue(abs(zoomedOut.x) < layout.worldBounds.right + 10_000f)
        val last = layout.focusCamera(99_999, 112f)
        val indices = layout.visibleIndices(last)
        assertTrue(indices.contains(99_999))
        assertTrue(indices.size < 150)
        assertTrue(indices.all { it in 0 until 100_000 })
        val zoomedIn = layout.transform(last, 640f, 360f, 0f, 0f, 100f)
        assertEquals(layout.maxZoom, zoomedIn.zoom, 0f)
        assertEquals(99_999, layout.nearestIndex(layout.center(99_999)))
    }

    @Test
    fun emptyAndSingleAlbumLibrariesHaveValidGeometry() {
        val empty = PanoramaLayout.create(390f, 700f, 1f, 0)
        assertTrue(empty.visibleIndices(empty.overviewCamera()).isEmpty())
        assertNull(empty.nearestIndex(empty.overviewCamera()))
        val single = PanoramaLayout.create(1280f, 670f, 1f, 1)
        assertEquals(1, single.columns)
        val item = single.placement(0, single.overviewCamera())
        assertEquals(single.viewport.centerX, item.caseBounds.centerX, .01f)
    }

    @Test
    fun largeViewportCanReachTheActualNormalShelfScaleBeforeReturning() {
        val layout = PanoramaLayout.create(2560f, 1440f, 1f, 96)
        val normalScene = PanoramaRect(0f, 0f, 2560f, 1440f)
        val overview = layout.overviewCamera(19)
        val enlarged = layout.transform(overview, layout.viewport.centerX, layout.viewport.centerY,
            panX = 0f, panY = 0f, zoomChange = 100f)
        assertTrue(layout.maxZoom > PanoramaLayout.MAX_ZOOM)
        assertEquals(layout.maxZoom, enlarged.zoom, .001f)
        assertTrue(layout.caseScaleRelativeTo(normalScene, enlarged) >= 1f)
        assertEquals(layout.maxZoom, layout.clamp(enlarged.copy(zoom = 100f)).zoom, .001f)
        val focus = layout.focusCamera(19, 112f, zoomOverride = 100f)
        assertEquals(layout.maxZoom, focus.zoom, .001f)
    }
}
