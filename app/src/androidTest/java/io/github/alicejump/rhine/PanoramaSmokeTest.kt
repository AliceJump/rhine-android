package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alicejump.rhine.ui.ArchiveAlbumFocusKey
import io.github.alicejump.rhine.ui.ArchiveLaneKey
import io.github.alicejump.rhine.ui.ArchiveSlotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real exposed-edge touches and pinch gestures exercise the dense shelf's selection contract. */
@RunWith(AndroidJUnit4::class)
class PanoramaSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val shelf get() = ArchiveSceneProbe(compose)

    @Test
    fun denseShelfKeepsExplicitSelectionThroughBrowsingAndUserZoom() {
        val originalOrientation = compose.activity.requestedOrientation
        try {
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitArchive()
            shelf.assertSelected("")
            assertEquals("The anchor is a browsing position, not a selection", "demo-0", shelf.anchorId())
            assertTrue("All cases start lowered", shelf.visibleAlbums().all { it.config[ArchiveAlbumFocusKey] < .001f })
            compose.onNodeWithContentDescription("取消选择").assertIsNotEnabled()
            shelf.pinch(.30f)
            shelf.awaitMode(true)
            shelf.assertSelected("")
            compose.onNodeWithText("01  档案").assertDoesNotExist()
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
            val emptyZoom = shelf.zoom()
            shelf.pinch(3.2f)
            assertTrue("No-selection zoom still magnifies the panorama", shelf.zoom() > emptyZoom)
            shelf.awaitMode(true)
            shelf.assertSelected("")
            overview()
            assertToolbarDoesNotSelectAnOccludedAlbum()
            SmokeScreenshots.capture("06-panorama-phone-overview", compose.activity).recycle()

            // This target is the case's exposed sliver, not its occluded full face.
            shelf.touchAlbum("demo-1")
            shelf.assertSelected("demo-1")
            assertEquals(1f, shelf.focus("demo-1"), .01f)
            compose.onNodeWithText("QUIET ORBIT").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放选中专辑").assertIsNotEnabled()
            compose.onNodeWithText("尚未播放").assertIsDisplayed()
            assertCardInsideScene()
            SmokeScreenshots.capture("07-panorama-phone-focus", compose.activity).recycle()
            compose.onNodeWithContentDescription("全景专辑详情").performClick()
            compose.onNodeWithText("ALBUM FILE / 曲目档案").assertIsDisplayed()
            SmokeScreenshots.capture("08-panorama-phone-details", compose.activity).recycle()
            pressBack()
            shelf.assertSelected("demo-1")
            shelf.pan(.10f, .04f)
            shelf.assertSelected("demo-1")
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
            overview()
            shelf.assertSelected("demo-1")
            assertEquals("Overview must not lower the selection", 1f, shelf.focus("demo-1"), .01f)
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()

            shelf.touchAlbum("demo-5")
            shelf.assertSelected("demo-5")
            assertEquals(1f, shelf.focus("demo-5"), .01f)
            shelf.awaitMode(true) // Programmatic focus must not trigger automatic exit.
            assertCardInsideScene()
            zoomSelectedBackToShelf()
            shelf.assertSelected("demo-5")
            assertEquals(1f, shelf.focus("demo-5"), .01f)
            compose.onNodeWithTag("panorama-mode").assertDoesNotExist()
            compose.onNodeWithContentDescription("播放").assertIsNotEnabled()

            compose.onNodeWithText("全景浏览").performClick()
            shelf.awaitMode(true)
            shelf.assertSelected("demo-5")
            assertEquals("Re-entering must preserve the lift", 1f, shelf.focus("demo-5"), .01f)
            overview()
            val anchorBeforeCancel = shelf.anchorId()
            shelf.touchBlank()
            shelf.assertSelected("")
            assertEquals(anchorBeforeCancel, shelf.anchorId())
            assertTrue("Blank cancellation lowers the cases", shelf.visibleAlbums().all { it.config[ArchiveAlbumFocusKey] < .001f })
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
            pressBack()
            shelf.awaitMode(false)
            shelf.assertSelected("")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test
    fun tabletChangesVisibleRangeWithoutReaddressingTheShelfOrLosingSelection() {
        val originalOrientation = compose.activity.requestedOrientation
        val originalSize = Regex("Override size: ([0-9]+x[0-9]+)").find(MotionTestDevice.shell("wm size"))?.groupValues?.get(1)
        val originalDensity = Regex("Override density: ([0-9]+)").find(MotionTestDevice.shell("wm density"))?.groupValues?.get(1)
        try {
            MotionTestDevice.shell("wm size 1600x2560")
            MotionTestDevice.shell("wm density 320")
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            compose.waitUntil(10_000) { compose.activity.resources.configuration.smallestScreenWidthDp >= 720 }
            awaitArchive()
            compose.onNodeWithText("全景浏览").performClick()
            shelf.awaitMode(true)
            shelf.assertSelected("")
            val portraitWidth = shelf.worldWidth()
            val portraitHeight = shelf.worldHeight()
            val addresses = visibleAddresses()
            assertTrue("The dense shelf exposes individually hittable cases", addresses.size >= 4)
            SmokeScreenshots.capture("09-panorama-tablet-portrait", compose.activity).recycle()
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            shelf.awaitMode(true)
            assertTrue("Landscape reveals a wider range", shelf.worldWidth() > portraitWidth)
            assertTrue("Portrait reveals a taller range", shelf.worldHeight() < portraitHeight)
            val landscapeAddresses = visibleAddresses()
            val shared = addresses.keys.intersect(landscapeAddresses.keys)
            assertTrue("Rotation must leave common albums visible", shared.isNotEmpty())
            shared.forEach { assertEquals("Stable shelf address of $it", addresses[it], landscapeAddresses[it]) }
            SmokeScreenshots.capture("10-panorama-tablet-landscape", compose.activity).recycle()

            shelf.touchAlbum("demo-5")
            shelf.assertSelected("demo-5")
            shelf.awaitMode(true)
            assertCardInsideScene()
            SmokeScreenshots.capture("11-panorama-tablet-landscape-focus", compose.activity).recycle()
            val selectedAddress = shelf.address("demo-5")
            // Free-camera rotation preserves world center; selected-card framing may recompose.
            shelf.pan(-.08f, .05f)
            shelf.assertSelected("demo-5")
            val camera = shelf.cameraCenter()
            val zoom = shelf.zoom()
            val anchor = shelf.anchorId()
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            shelf.awaitMode(true)
            shelf.assertSelected("demo-5")
            assertEquals(anchor, shelf.anchorId())
            assertEquals(zoom, shelf.zoom(), .005f)
            assertEquals(camera.x, shelf.cameraCenter().x, 3f)
            assertEquals(camera.y, shelf.cameraCenter().y, 3f)
            assertEquals(selectedAddress, shelf.address("demo-5"))
            SmokeScreenshots.capture("12-panorama-tablet-portrait-focus", compose.activity).recycle()
            assertOffCenterZoomDoesNotExit()
            shelf.assertSelected("demo-5")
            compose.onNodeWithContentDescription("退出全景").performClick()
            shelf.awaitMode(false)
            shelf.assertSelected("demo-5")
        } finally {
            MotionTestDevice.shell("wm size " + (originalSize ?: "reset"))
            MotionTestDevice.shell("wm density " + (originalDensity ?: "reset"))
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    private fun zoomSelectedBackToShelf() {
        shelf.moveSelectedTo()
        val center = shelf.viewportCenter() + shelf.scene().boundsInRoot.topLeft
        shelf.pinch(3.2f, center)
        shelf.awaitMode(false)
    }

    private fun assertOffCenterZoomDoesNotExit() {
        shelf.touchAlbum("demo-5")
        shelf.awaitMode(true)
        val scene = shelf.canvasBounds()
        val viewport = shelf.viewportCenter()
        shelf.moveSelectedTo(viewport + Offset(scene.width * .30f, 0f))
        assertTrue("The negative case must actually be off-center", (shelf.caseCenter("demo-5") - viewport).getDistance() > scene.width * .15f)
        val before = shelf.zoom()
        shelf.pinch(3.2f, viewport + shelf.scene().boundsInRoot.topLeft)
        assertTrue("An off-center gesture still zooms", shelf.zoom() > before)
        shelf.awaitMode(true)
    }

    private fun overview() {
        compose.onNodeWithContentDescription("总览").performClick()
        compose.waitForIdle()
    }

    private fun awaitArchive() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("DEMO ARCHIVE / 示例档案").fetchSemanticsNodes().isNotEmpty() }
        shelf.awaitScene()
        shelf.awaitMode(false)
    }

    private fun rotateTo(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(10_000) {
            runCatching {
                val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
                compose.activity.resources.configuration.orientation == expected &&
                    if (landscape) bounds.width > bounds.height else bounds.height > bounds.width
            }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private fun visibleAddresses(): Map<String, Pair<Int, Int>> = shelf.visibleAlbums().associate {
        it.config[SemanticsProperties.TestTag] to (it.config[ArchiveLaneKey] to it.config[ArchiveSlotKey])
    }

    private fun assertToolbarDoesNotSelectAnOccludedAlbum() {
        val canvas = compose.onNodeWithTag("panorama-canvas")
        val scene = canvas.fetchSemanticsNode().boundsInRoot
        val exposed = shelf.album("demo-0").boundsInRoot
        val heading = compose.onNodeWithText("ARCHIVE / PANORAMA").fetchSemanticsNode().boundsInRoot
        val start = exposed.center - scene.topLeft
        val behindHeading = Offset(start.x, heading.center.y - scene.top)
        canvas.performTouchInput {
            down(start)
            moveTo(behindHeading, delayMillis = 250)
            up()
        }
        compose.waitForIdle()
        canvas.performTouchInput { click(behindHeading) }
        shelf.assertSelected("")
        compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
        overview()
    }

    private fun assertCardInsideScene() {
        val scene = shelf.canvasBounds()
        val card = compose.onNodeWithTag("panorama-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The action card must remain inside the scene: $card within $scene", contains(scene, card))
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f
}
