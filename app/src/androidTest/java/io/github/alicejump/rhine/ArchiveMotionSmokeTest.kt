package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alicejump.rhine.ui.ArchiveExpansionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Freeze actual intermediate frames so an endpoint-only swap cannot pass as continuous motion. */
@RunWith(AndroidJUnit4::class)
class ArchiveMotionSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val shelf get() = ArchiveSceneProbe(compose)

    @Test
    fun albumEntitiesMoveContinuouslyAndCanReverseBeforeExpansionFinishes() {
        val originalOrientation = compose.activity.requestedOrientation
        MotionTestDevice.withAnimations {
            try {
                compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10_000) {
                    runCatching {
                        val window = compose.activity.window.decorView
                        compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                            window.height > window.width &&
                            compose.onAllNodesWithTag("archive-album-demo-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }.getOrDefault(false)
                }
                compose.waitForIdle()
                shelf.assertSelected("")
                val sceneId = scene().id
                val albumId = album("demo-0").id
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("archive-album-demo-0", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shelf.assertSelected("demo-0")
                assertEquals(1f, shelf.focus("demo-0"), .01f)
                val normal = shelf.caseBounds("demo-0")

                compose.onNodeWithText("全景浏览").performClick()
                advanceUntil("Entering panorama must have an intermediate state") { expansion() in 0.25f..0.75f }
                val entering = shelf.caseBounds("demo-0")
                assertEquals("Expansion must retain the scene", sceneId, scene().id)
                assertEquals("The same album entity must survive expansion", albumId, album("demo-0").id)
                assertEquals("The selected case must stay raised while entering", 1f, shelf.focus("demo-0"), .01f)
                assertTrue("The album must move or resize before expansion finishes", materiallyDifferent(normal, entering))
                SmokeScreenshots.capture("13-motion-enter-middle", compose.activity).recycle()

                compose.waitForIdle()
                val reversalOrigin = shelf.caseBounds("demo-0")
                val turningPoint = expansion()
                // Android performClick injects a timed down/up gesture. Use the public
                // button action here so the only motion step is the frame advanced below.
                compose.onNodeWithContentDescription("退出全景").performSemanticsAction(SemanticsActions.OnClick)
                compose.mainClock.advanceTimeByFrame()
                val afterReverse = shelf.caseBounds("demo-0")
                val reversalProgress = expansion()
                assertTrue(
                    "Reversal must retain an intermediate state (before=$turningPoint, after=$reversalProgress)",
                    reversalProgress > 0f && reversalProgress < 1f,
                )
                assertEquals("Reversing must retain the same album entity", albumId, album("demo-0").id)
                assertTrue(
                    "Changing direction must not teleport the album back to an endpoint (before=$turningPoint, after=$reversalProgress)",
                    centerDistance(reversalOrigin, afterReverse) < maxOf(24f, centerDistance(normal, reversalOrigin) * .5f),
                )
                // animateFloatAsState can consume one last old-target frame before the
                // retarget is applied. Require the subsequent motion to actually reverse.
                advanceUntil("The in-flight expansion should move back toward the shelf (turn=$turningPoint)") {
                    expansion() > 0f && expansion() < turningPoint * .7f
                }
                SmokeScreenshots.capture("14-motion-reverse-middle", compose.activity).recycle()
                settle()
                assertEquals(0f, expansion(), .001f)
                assertEquals(sceneId, scene().id)
                assertEquals(albumId, album("demo-0").id)

                val oldBeforeSelection = shelf.caseBounds("demo-0")
                val newBeforeSelection = shelf.caseBounds("demo-1")
                val nextId = album("demo-1").id
                compose.onNodeWithTag("archive-album-demo-1", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.OnClick)
                advanceUntil("The old selection must lower while the new one rises") {
                    runCatching { shelf.focus("demo-0") in .05f.. .95f && shelf.focus("demo-1") in .05f.. .95f }.getOrDefault(false)
                }
                shelf.assertSelected("demo-1")
                val oldDuringSelection = shelf.caseBounds("demo-0")
                val newDuringSelection = shelf.caseBounds("demo-1")
                assertTrue("The former selected case must yield its place", materiallyDifferent(oldBeforeSelection, oldDuringSelection))
                assertTrue("The new selected case must move into its place", materiallyDifferent(newBeforeSelection, newDuringSelection))
                assertEquals("Selecting a neighbour must preserve its identity", nextId, album("demo-1").id)
                SmokeScreenshots.capture("15-motion-selection-middle", compose.activity).recycle()
                settle()
                assertTrue("Selection must continue after the sampled intermediate frame", materiallyDifferent(newDuringSelection, shelf.caseBounds("demo-1")))
                assertEquals(0f, shelf.focus("demo-0"), .01f)
                assertEquals(1f, shelf.focus("demo-1"), .01f)

                compose.onNodeWithText("全景浏览").performClick()
                settle()
                assertEquals(1f, expansion(), .001f)
                assertEquals(1f, shelf.focus("demo-1"), .01f)
                compose.onNodeWithContentDescription("总览").performSemanticsAction(SemanticsActions.OnClick)
                settle()
                compose.onNodeWithTag("archive-album-demo-5", useUnmergedTree = true).performClick()
                settle()
                compose.onNodeWithText("GLASS MEMORY").assertIsDisplayed()
                val selectedId = album("demo-5").id
                assertEquals("Programmatic focus must not trigger automatic exit", 1f, expansion(), .001f)
                shelf.moveSelectedTo()
                settle()
                // Keep both actual pointers down while sampling the gesture-driven handoff.
                shelf.beginCenteredPinch().use { gesture ->
                    var scale = 1f
                    var beforeHandoff = shelf.caseBounds("demo-5")
                    repeat(14) {
                        if (expansion() > .95f) {
                            beforeHandoff = shelf.caseBounds("demo-5")
                            scale *= 1.1f
                            gesture.moveToScale(scale)
                            compose.mainClock.advanceTimeByFrame()
                        }
                    }
                    assertTrue("User expansion must pass through a continuous handoff: " + expansion(), expansion() in .05f.. .95f)
                    assertEquals(sceneId, scene().id)
                    assertEquals(selectedId, album("demo-5").id)
                    assertEquals(1f, shelf.focus("demo-5"), .01f)
                    val duringHandoff = shelf.caseBounds("demo-5")
                    assertTrue("Crossing the zoom gate must not teleport the case", centerDistance(beforeHandoff, duringHandoff) < maxOf(24f, beforeHandoff.width * .30f))
                    assertTrue("Crossing the zoom gate must retain its current apparent size", duringHandoff.width / beforeHandoff.width in .65f..1.35f)
                    SmokeScreenshots.capture("16-motion-exit-middle", compose.activity).recycle()
                    gesture.moveToScale(3.5f)
                    compose.mainClock.advanceTimeByFrame()
                }
                settle()
                assertEquals(0f, expansion(), .001f)
                assertEquals(sceneId, scene().id)
                assertEquals(selectedId, album("demo-5").id)
                shelf.assertSelected("demo-5")
                compose.onNodeWithText("GLASS MEMORY").assertExists()

                compose.onNodeWithText("全景浏览").performSemanticsAction(SemanticsActions.OnClick)
                settle()
                assertEquals(1f, shelf.focus("demo-5"), .01f)
                compose.onNodeWithContentDescription("总览").performSemanticsAction(SemanticsActions.OnClick)
                settle()
                shelf.touchBlank()
                shelf.assertSelected("")
                advanceUntil("Explicit blank cancellation must lower the case through an intermediate lift") {
                    shelf.focus("demo-5") in .05f.. .95f
                }
                SmokeScreenshots.capture("17-motion-cancel-middle", compose.activity).recycle()
                settle()
                assertEquals(0f, shelf.focus("demo-5"), .01f)
            } finally {
                compose.mainClock.autoAdvance = true
                compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
            }
        }
    }

    private fun scene() = compose.onNodeWithTag("archive-scene", useUnmergedTree = true).fetchSemanticsNode()

    private fun album(id: String) = compose.onNodeWithTag("archive-album-$id", useUnmergedTree = true).fetchSemanticsNode()

    private fun expansion(): Float = scene().config[ArchiveExpansionKey]

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun advanceUntil(message: String, condition: () -> Boolean) {
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return
        }
        throw AssertionError(message)
    }

    private fun centerDistance(first: Rect, second: Rect): Float = (first.center - second.center).getDistance()

    private fun materiallyDifferent(first: Rect, second: Rect): Boolean =
        centerDistance(first, second) > 1f || abs(first.width - second.width) > 1f || abs(first.height - second.height) > 1f
}
