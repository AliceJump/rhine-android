package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import androidx.lifecycle.Lifecycle
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real exposed-edge touches and pinch gestures exercise the dense shelf's selection contract. */
@RunWith(AndroidJUnit4::class)
class PanoramaSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val shelf get() = ArchiveSceneProbe(compose)
    private var progress: PanoramaTestProgress? = null

    @Test
    fun denseShelfKeepsExplicitSelectionThroughBrowsingAndUserZoom() = withProgress("phone dense shelf") {
        val originalOrientation = compose.activity.requestedOrientation
        try {
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitArchive()
            shelf.assertSelected("")
            assertEquals("The anchor is a browsing position, not a selection", "demo-0", shelf.anchorId())
            assertTrue("All cases start lowered", shelf.visibleAlbums().all { it.config[ArchiveAlbumFocusKey] < .001f })
            compose.onNodeWithContentDescription("取消选择").assertIsNotEnabled()
            step("phone enter and directly exit without moving the camera")
            compose.onNodeWithText("全景浏览").performClick()
            shelf.awaitMode(true)
            shelf.assertSelected("")
            compose.onNodeWithContentDescription("退出全景").performClick()
            shelf.awaitMode(false)
            shelf.assertSelected("")
            assertEquals("An untouched overview must return to the original browsing position", "demo-0", shelf.anchorId())
            step("phone enter panorama with real pinch")
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
            step("phone select demo-1 and open details")
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

            step("phone select demo-5 and zoom back to shelf")
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

            step("phone re-enter and cancel via blank space")
            compose.onNodeWithText("全景浏览").performClick()
            shelf.awaitMode(true)
            shelf.assertSelected("demo-5")
            assertEquals("Re-entering must preserve the lift", 1f, shelf.focus("demo-5"), .01f)
            overview()
            shelf.touchBlank()
            shelf.assertSelected("")
            // The whole-room overview centers the middle lane between its two middle slots.
            // Either equally near case is a valid return position when selection is cleared.
            val cancelledAnchor = shelf.anchorId()
            assertTrue("Cancellation must anchor the current whole-room view: $cancelledAnchor",
                cancelledAnchor in setOf("demo-11", "demo-12"))
            assertTrue("Blank cancellation lowers the cases", shelf.visibleAlbums().all { it.config[ArchiveAlbumFocusKey] < .001f })
            compose.onNodeWithTag("panorama-actions").assertDoesNotExist()
            pressBack()
            shelf.awaitMode(false)
            shelf.assertSelected("")
            assertEquals("Returning to the normal shelf must preserve the browsing position", cancelledAnchor, shelf.anchorId())
        } finally {
            step("phone restore orientation")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test
    fun tabletChangesVisibleRangeWithoutReaddressingTheShelfOrLosingSelection() = withProgress("tablet dense shelf") {
        val originalOrientation = compose.activity.requestedOrientation
        val originalSize = Regex("Override size: ([0-9]+x[0-9]+)").find(MotionTestDevice.shell("wm size"))?.groupValues?.get(1)
        val originalDensity = Regex("Override density: ([0-9]+)").find(MotionTestDevice.shell("wm density"))?.groupValues?.get(1)
        try {
            step("tablet configure native size and density")
            MotionTestDevice.shell("wm size 1600x2560")
            MotionTestDevice.shell("wm density 320")
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            compose.waitUntil(10_000) { compose.activity.resources.configuration.smallestScreenWidthDp >= 720 }
            awaitArchive()
            step("tablet enter panorama")
            compose.onNodeWithText("全景浏览").performClick()
            shelf.awaitMode(true)
            shelf.assertSelected("")
            val portraitWidth = shelf.worldWidth()
            val portraitHeight = shelf.worldHeight()
            val addresses = visibleAddresses()
            assertTrue("The dense shelf exposes individually hittable cases", addresses.size >= 4)
            step("tablet capture portrait overview")
            SmokeScreenshots.capture("09-panorama-tablet-portrait", compose.activity).recycle()
            step("tablet portrait overview captured")
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, requireAssetContinuity = true)
            shelf.awaitMode(true)
            step("tablet check landscape viewport and stable addresses")
            assertTrue("Landscape reveals a wider range", shelf.worldWidth() > portraitWidth)
            assertTrue("Portrait reveals a taller range", shelf.worldHeight() < portraitHeight)
            val landscapeAddresses = visibleAddresses()
            val shared = addresses.keys.intersect(landscapeAddresses.keys)
            assertTrue("Rotation must leave common albums visible", shared.isNotEmpty())
            shared.forEach { assertEquals("Stable shelf address of $it", addresses[it], landscapeAddresses[it]) }
            step("tablet capture landscape overview")
            SmokeScreenshots.capture("10-panorama-tablet-landscape", compose.activity).recycle()

            step("tablet select demo-5")
            shelf.touchAlbum("demo-5")
            shelf.assertSelected("demo-5")
            shelf.awaitMode(true)
            assertCardInsideScene()
            step("tablet capture landscape selection")
            SmokeScreenshots.capture("11-panorama-tablet-landscape-focus", compose.activity).recycle()
            val selectedAddress = shelf.address("demo-5")
            // Free-camera rotation preserves world center; selected-card framing may recompose.
            step("tablet pan into free camera mode")
            shelf.pan(-.08f, .05f)
            shelf.assertSelected("demo-5")
            val camera = shelf.cameraCenter()
            val zoom = shelf.zoom()
            val anchor = shelf.anchorId()
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requireAssetContinuity = true)
            shelf.awaitMode(true)
            step("tablet check free camera and selection after rotation")
            shelf.assertSelected("demo-5")
            assertEquals(anchor, shelf.anchorId())
            assertEquals(zoom, shelf.zoom(), .005f)
            assertEquals(camera.x, shelf.cameraCenter().x, 3f)
            assertEquals(camera.y, shelf.cameraCenter().y, 3f)
            assertEquals(selectedAddress, shelf.address("demo-5"))
            step("tablet capture portrait selection")
            SmokeScreenshots.capture("12-panorama-tablet-portrait-focus", compose.activity).recycle()
            step("tablet off-center zoom must remain in panorama")
            assertOffCenterZoomDoesNotExit()
            shelf.assertSelected("demo-5")
            step("tablet explicitly exit panorama")
            compose.onNodeWithContentDescription("退出全景").performClick()
            shelf.awaitMode(false)
            shelf.assertSelected("demo-5")
        } finally {
            step("tablet restore native size, density and orientation")
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

    private fun rotateTo(orientation: Int, requireAssetContinuity: Boolean = false) {
        val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        var previousActivity: MainActivity? = null
        var previousOrientation = Configuration.ORIENTATION_UNDEFINED
        compose.activityRule.scenario.onActivity {
            previousActivity = it
            previousOrientation = it.resources.configuration.orientation
            step("rotation requested: $previousOrientation -> $expected, activity=${System.identityHashCode(it)}")
            it.requestedOrientation = orientation
        }
        val recreationExpected = previousOrientation != expected
        fun ready(window: NativeWindow): Boolean =
            (!recreationExpected || window.activity !== previousActivity) && window.resumed &&
                window.attached && window.laidOut && window.shown && window.orientation == expected &&
                window.width > 0 && window.height > 0 &&
                if (landscape) window.width > window.height else window.height > window.width

        var stable: NativeWindow? = null
        MotionTestDevice.await("Rotation must produce a stable resumed native window ($previousOrientation -> $expected)") {
            val first = nativeWindow() ?: return@await false
            if (!ready(first)) return@await false
            // Choreographer belongs to the main thread, so this wait survives old View removal.
            // Do not enter Compose synchronization while its previous root can still detach.
            MotionTestDevice.awaitFrames(2)
            val second = nativeWindow() ?: return@await false
            val sameWindow = ready(second) && first.activity === second.activity && first.view === second.view &&
                first.width == second.width && first.height == second.height
            if (sameWindow) stable = second
            sameWindow
        }
        val window = checkNotNull(stable)
        step("rotation native ready: activity=${System.identityHashCode(window.activity)}, window=${System.identityHashCode(window.view)}, ${window.width}x${window.height}")
        compose.waitForIdle()
        if (requireAssetContinuity) {
            assertTrue("The first stable rotated scene must retain the loaded case material", shelf.caseAssetReady())
        }
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The Compose root must match the stable native orientation", if (landscape) bounds.width > bounds.height else bounds.height > bounds.width)
        step("rotation Compose sync done: $expected")
    }

    private fun nativeWindow(): NativeWindow? {
        var window: NativeWindow? = null
        try {
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView
                window = NativeWindow(activity, view, activity.resources.configuration.orientation,
                    view.width, view.height, activity.lifecycle.currentState == Lifecycle.State.RESUMED,
                    view.isAttachedToWindow, view.isLaidOut, view.isShown)
            }
        } catch (_: IllegalStateException) {
            // ActivityScenario can briefly have no current Activity during recreation.
        } catch (failure: NullPointerException) {
            // Test core 1.6 also reports that normal recreation gap with this exact exception.
            if (failure.message != "Cannot run onActivity since Activity has been destroyed already") throw failure
        }
        return window
    }

    private data class NativeWindow(
        val activity: MainActivity,
        val view: View,
        val orientation: Int,
        val width: Int,
        val height: Int,
        val resumed: Boolean,
        val attached: Boolean,
        val laidOut: Boolean,
        val shown: Boolean,
    )

    private fun withProgress(name: String, block: () -> Unit) {
        val diagnostic = PanoramaTestProgress(name, Thread.currentThread())
        progress = diagnostic
        try {
            block()
        } finally {
            progress = null
            diagnostic.close()
        }
    }

    private fun step(name: String) {
        progress?.mark(name)
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

/** Reports a blocked test without changing its result or asking the blocked UI thread to run code. */
private class PanoramaTestProgress(name: String, private val testThread: Thread) : AutoCloseable {
    private data class Stage(val name: String, val since: Long)
    private val current = AtomicReference(Stage(name, SystemClock.uptimeMillis()))
    private val watcher = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "panorama-test-watchdog").apply { isDaemon = true }
    }
    private var reported: Stage? = null

    init {
        mark(name)
        watcher.scheduleWithFixedDelay({
            val stage = current.get()
            val elapsed = SystemClock.uptimeMillis() - stage.since
            if (elapsed >= 60_000 && reported !== stage) {
                reported = stage
                Log.e("PanoramaSmoke", "No test progress for ${elapsed}ms at: ${stage.name}")
                dump("instrumentation", testThread)
                dump("main", Looper.getMainLooper().thread)
            }
        }, 60, 5, TimeUnit.SECONDS)
    }

    fun mark(name: String) {
        current.set(Stage(name, SystemClock.uptimeMillis()))
        Log.i("PanoramaSmoke", name)
    }

    private fun dump(label: String, thread: Thread) {
        val lines = thread.stackTrace.map { "    at $it" }
        lines.chunked(20).forEachIndexed { index, part ->
            Log.e("PanoramaSmoke", "$label thread '${thread.name}' state=${thread.state}, stack part $index\n${part.joinToString("\n")}")
        }
    }

    override fun close() {
        watcher.shutdownNow()
    }
}
