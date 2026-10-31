package io.github.alicejump.rhine.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaGeometryTest {
    @Test
    fun rotatingTabletUsesMoreColumnsAndFewerRowsInLandscape() {
        val portrait = PanoramaLayout.create(800f, 1150f, 1f, 24)
        val landscape = PanoramaLayout.create(1280f, 670f, 1f, 24)
        assertTrue(landscape.columns > portrait.columns)
        assertTrue(landscape.rows < portrait.rows)
        val phone = PanoramaLayout.create(390f, 700f, 1f, 24)
        assertTrue(phone.columns < portrait.columns)
        assertTrue(phone.rows > portrait.rows)
    }

    @Test
    fun dimensionsAreBasedOnTheAvailableViewportAndDensity() {
        val fullWidth = PanoramaLayout.create(1200f, 760f, 1f, 40)
        val splitWindow = PanoramaLayout.create(460f, 760f, 1f, 40)
        val highDensity = PanoramaLayout.create(2400f, 1520f, 2f, 40)
        assertTrue(splitWindow.columns < fullWidth.columns)
        assertEquals(fullWidth.columns, highDensity.columns)
        assertEquals(fullWidth.cellWidth * 2f, highDensity.cellWidth, .01f)
    }

    @Test
    fun semanticTargetsStayInsideTheActuallyClickableCase() {
        val layout = PanoramaLayout.create(390f, 700f, 1f, 24)
        val item = layout.placement(4, layout.overviewCamera())
        val bounds = item.accessibleBounds
        assertTrue(bounds.width >= 48f)
        assertTrue(bounds.height >= 48f)
        for (x in listOf(bounds.left, bounds.centerX, bounds.right)) {
            for (y in listOf(bounds.top, bounds.centerY, bounds.bottom)) assertTrue(item.contains(x, y))
        }
        assertFalse(item.contains(item.x, item.y))
    }

    @Test
    fun edgeAlbumsLeaveRoomForTheMeasuredActionCard() {
        for ((width, height) in listOf(390f to 700f, 800f to 1150f, 1280f to 670f)) {
            val layout = PanoramaLayout.create(width, height, 1f, 120)
            for (index in listOf(0, 5, 119)) {
                val camera = layout.focusCamera(index, 112f)
                val item = layout.placement(index, camera)
                val card = layout.cardBounds(item, minOf(272f, width - 24f), 112f)
                assertTrue(card.left >= 0f)
                assertTrue(card.right <= width)
                assertTrue(card.top >= layout.viewport.top)
                assertTrue(card.bottom <= item.caseBounds.top)
                assertTrue(item.caseBounds.bottom <= layout.viewport.bottom + .01f)
                assertTrue(layout.visibleIndices(camera).contains(index))
            }
        }
    }

    @Test
    fun shortLandscapeKeepsTheWholeActionCardInsideTheCanvas() {
        val layout = PanoramaLayout.create(720f, 240f, 1f, 24)
        val cardHeight = layout.viewport.height * .55f
        val camera = layout.focusCamera(23, cardHeight)
        val card = layout.cardBounds(layout.placement(23, camera), 272f, cardHeight)
        assertTrue(card.left >= 0f)
        assertTrue(card.top >= 0f)
        assertTrue(card.right <= layout.width)
        assertTrue(card.bottom <= layout.height)
        assertEquals(cardHeight, card.height, .01f)
    }

    @Test
    fun zoomKeepsTheContentUnderThePinchCentroidInPlace() {
        val layout = PanoramaLayout.create(800f, 1000f, 1f, 160)
        val before = PanoramaCamera(layout.worldWidth / 2f, layout.worldHeight / 2f, 1.2f)
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
        assertTrue(zoomedOut.x >= 0f)
        assertTrue(zoomedOut.y >= -layout.viewport.height * .35f / zoomedOut.zoom)
        val last = layout.focusCamera(99_999, 112f)
        val indices = layout.visibleIndices(last)
        assertTrue(indices.contains(99_999))
        assertTrue(indices.size < 150)
        assertTrue(indices.all { it in 0 until 100_000 })
        val zoomedIn = layout.transform(last, 640f, 360f, 0f, 0f, 100f)
        assertEquals(PanoramaLayout.MAX_ZOOM, zoomedIn.zoom, 0f)
    }

    @Test
    fun emptyAndSingleAlbumLibrariesHaveValidGeometry() {
        val empty = PanoramaLayout.create(390f, 700f, 1f, 0)
        assertTrue(empty.visibleIndices(empty.overviewCamera()).isEmpty())
        val single = PanoramaLayout.create(1280f, 670f, 1f, 1)
        assertEquals(1, single.columns)
        val item = single.placement(0, single.overviewCamera())
        assertEquals(single.viewport.centerX, item.caseBounds.centerX, .01f)
    }
}
