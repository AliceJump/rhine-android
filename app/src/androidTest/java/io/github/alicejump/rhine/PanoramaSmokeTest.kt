package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch, orientation changes and screenshots cover the panorama's user-visible contract. */
@RunWith(AndroidJUnit4::class)
class PanoramaSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pinchSelectInspectAndExitKeepPreviewAlbumsUnplayable() {
        val originalOrientation = compose.activity.requestedOrientation
        try {
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitArchive()
            compose.onNodeWithTag("archive-shelf").performTouchInput {
                val middle = center
                val startDistance = width * .32f
                val endDistance = width * .10f
                down(0, middle - Offset(startDistance, 0f))
                down(1, middle + Offset(startDistance, 0f))
                for (step in 1..16) {
                    val distance = startDistance + (endDistance - startDistance) * step / 16f
                    updatePointerTo(0, middle - Offset(distance, 0f))
                    updatePointerTo(1, middle + Offset(distance, 0f))
                    move(delayMillis = 16)
                }
                up(1)
                up(0)
            }
            awaitTag("panorama-mode")
            compose.onNodeWithText("打开档案").assertDoesNotExist()
            compose.onNodeWithText("01  档案").assertDoesNotExist()
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
            assertToolbarDoesNotSelectAnOccludedAlbum()
            SmokeScreenshots.capture("06-panorama-phone-overview", compose.activity).recycle()

            touchAlbum("demo-1")
            awaitTag("panorama-actions")
            compose.onNodeWithText("QUIET ORBIT").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放选中专辑").assertIsNotEnabled()
            compose.onNodeWithText("尚未播放").assertIsDisplayed()
            assertCardInsideScene()
            SmokeScreenshots.capture("07-panorama-phone-focus", compose.activity).recycle()

            compose.onNodeWithContentDescription("全景专辑详情").performClick()
            compose.onNodeWithText("ALBUM FILE / 曲目档案").assertIsDisplayed()
            SmokeScreenshots.capture("08-panorama-phone-details", compose.activity).recycle()
            pressBack()
            awaitTag("panorama-actions")
            compose.onNodeWithText("QUIET ORBIT").assertIsDisplayed()

            compose.onNodeWithContentDescription("退出全景").performClick()
            awaitTag("archive-shelf")
            compose.onNodeWithTag("panorama-mode").assertDoesNotExist()
            compose.onNodeWithText("QUIET ORBIT").assertExists()
            compose.onNodeWithContentDescription("播放").assertIsNotEnabled()

            compose.onNodeWithText("全景浏览").performClick()
            awaitTag("panorama-mode")
            pressBack()
            awaitTag("archive-shelf")
            compose.onNodeWithTag("panorama-mode").assertDoesNotExist()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test
    fun tabletReflowsTheCollectionAndKeepsTheFocusedAlbumWhenRotated() {
        val originalOrientation = compose.activity.requestedOrientation
        val originalSize = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
        val originalDensity = Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1)
        try {
            // Exercise an actual tablet-sized Android window on the same CI emulator. These
            // overrides are restored even if an assertion fails, so later audio/UI tests are isolated.
            shell("wm size 1600x2560")
            shell("wm density 320")
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            compose.waitUntil(10_000) {
                runCatching { compose.activity.resources.configuration.smallestScreenWidthDp >= 720 }.getOrDefault(false)
            }
            awaitArchive()
            compose.onNodeWithText("全景浏览").performClick()
            awaitTag("panorama-mode")
            val portraitColumns = visibleColumns()
            assertTrue("The tablet portrait view should expose several columns", portraitColumns >= 2)
            SmokeScreenshots.capture("09-panorama-tablet-portrait", compose.activity).recycle()

            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            awaitTag("panorama-mode")
            val landscapeColumns = visibleColumns()
            assertTrue(
                "Landscape should display more albums across a row than portrait: $landscapeColumns versus $portraitColumns",
                landscapeColumns > portraitColumns,
            )
            SmokeScreenshots.capture("10-panorama-tablet-landscape", compose.activity).recycle()

            touchAlbum("demo-5")
            awaitTag("panorama-actions")
            compose.onNodeWithText("GLASS MEMORY").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放选中专辑").assertIsNotEnabled()
            assertCardInsideScene()
            SmokeScreenshots.capture("11-panorama-tablet-landscape-focus", compose.activity).recycle()

            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitTag("panorama-actions")
            compose.onNodeWithText("GLASS MEMORY").assertIsDisplayed()
            compose.onNodeWithText("尚未播放").assertIsDisplayed()
            assertCardInsideScene()
            SmokeScreenshots.capture("12-panorama-tablet-portrait-focus", compose.activity).recycle()

            compose.onNodeWithContentDescription("退出全景").performClick()
            awaitTag("archive-shelf")
            compose.onNodeWithText("GLASS MEMORY").assertIsDisplayed()
        } finally {
            shell("wm size ${originalSize ?: "reset"}")
            shell("wm density ${originalDensity ?: "reset"}")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    private fun awaitArchive() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("DEMO ARCHIVE / 示例档案").fetchSemanticsNodes().isNotEmpty()
        }
        awaitTag("archive-shelf")
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().any {
                it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0
            }
        }
        compose.waitForIdle()
    }

    private fun rotateTo(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val expectedConfiguration = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(10_000) {
            runCatching {
                val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
                compose.activity.resources.configuration.orientation == expectedConfiguration &&
                    if (landscape) bounds.width > bounds.height else bounds.height > bounds.width
            }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private fun touchAlbum(id: String) {
        compose.onNodeWithTag("panorama-album-$id", useUnmergedTree = true)
            .assertIsDisplayed()
            .performTouchInput { click(center) }
        compose.waitForIdle()
    }

    private fun assertToolbarDoesNotSelectAnOccludedAlbum() {
        val canvas = compose.onNodeWithTag("panorama-canvas")
        val scene = canvas.fetchSemanticsNode().boundsInRoot
        val album = compose.onNodeWithTag("panorama-album-demo-0", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText("ARCHIVE / PANORAMA").fetchSemanticsNode().boundsInRoot
        val start = album.center - scene.topLeft
        val behindHeading = Offset(start.x, heading.center.y - scene.top)
        assertTrue("The first album should start below the toolbar", start.y > behindHeading.y)

        // Move the real hit target behind the opaque heading. The renderer may still preload
        // that album, but a touch in the toolbar must not select its now-invisible face.
        canvas.performTouchInput {
            down(start)
            moveTo(behindHeading, delayMillis = 250)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("panorama-album-demo-0", useUnmergedTree = true).assertDoesNotExist()
        canvas.performTouchInput { click(behindHeading) }
        compose.onNodeWithTag("panorama-actions").assertDoesNotExist()

        compose.onNodeWithContentDescription("总览").performClick()
        compose.waitForIdle()
    }

    private fun visibleColumns(): Int {
        val bounds = compose.onAllNodes(
            SemanticsMatcher("visible panorama album") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("panorama-album-") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0 && it.height > 0 }
        assertTrue("The collection must contain visible, individually selectable albums", bounds.isNotEmpty())
        // Count actual hit targets crossed by a horizontal scan line, independent of the
        // renderer's column constants, density or any partially clipped first/last row.
        return bounds.maxOf { sample -> bounds.count { sample.center.y >= it.top && sample.center.y <= it.bottom } }
    }

    private fun assertCardInsideScene() {
        val scene = compose.onNodeWithTag("panorama-canvas").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("panorama-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The album action card must stay inside the visible scene: $card within $scene", contains(scene, card))
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
