package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                val sceneId = scene().id
                val albumId = album("demo-0").id
                val normal = album("demo-0").boundsInRoot
                compose.mainClock.autoAdvance = false

                compose.onNodeWithText("全景浏览").performClick()
                advanceUntil("Entering panorama must have an intermediate state") { expansion() in 0.25f..0.75f }
                val entering = album("demo-0").boundsInRoot
                val turningPoint = expansion()
                assertEquals("Expansion must retain the scene", sceneId, scene().id)
                assertEquals("The same album entity must survive expansion", albumId, album("demo-0").id)
                assertTrue("The album must move or resize before expansion finishes", materiallyDifferent(normal, entering))
                SmokeScreenshots.capture("13-motion-enter-middle", compose.activity).recycle()

                compose.onNodeWithContentDescription("退出全景").performClick()
                compose.mainClock.advanceTimeByFrame()
                val afterReverse = album("demo-0").boundsInRoot
                val reversalProgress = expansion()
                assertTrue(
                    "Reversal must retain an intermediate state (before=$turningPoint, after=$reversalProgress)",
                    reversalProgress > 0f && reversalProgress < 1f,
                )
                assertEquals("Reversing must retain the same album entity", albumId, album("demo-0").id)
                assertTrue(
                    "Changing direction must not teleport the album back to an endpoint (before=$turningPoint, after=$reversalProgress)",
                    centerDistance(entering, afterReverse) < maxOf(24f, centerDistance(normal, entering) * .5f),
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

                val oldBeforeSelection = album("demo-0").boundsInRoot
                val newBeforeSelection = album("demo-1").boundsInRoot
                val nextId = album("demo-1").id
                compose.onNodeWithContentDescription("下一张专辑").performClick()
                compose.mainClock.advanceTimeBy(112)
                val oldDuringSelection = album("demo-0").boundsInRoot
                val newDuringSelection = album("demo-1").boundsInRoot
                assertTrue("The former selected case must yield its place", materiallyDifferent(oldBeforeSelection, oldDuringSelection))
                assertTrue("The new selected case must move into its place", materiallyDifferent(newBeforeSelection, newDuringSelection))
                assertEquals("Selecting a neighbour must preserve its identity", nextId, album("demo-1").id)
                SmokeScreenshots.capture("15-motion-selection-middle", compose.activity).recycle()
                settle()
                assertTrue("Selection must continue after the sampled intermediate frame", materiallyDifferent(newDuringSelection, album("demo-1").boundsInRoot))

                compose.onNodeWithText("全景浏览").performClick()
                settle()
                assertEquals(1f, expansion(), .001f)
                compose.onNodeWithTag("archive-album-demo-5", useUnmergedTree = true).performClick()
                settle()
                compose.onNodeWithText("GLASS MEMORY").assertIsDisplayed()
                val selectedId = album("demo-5").id
                compose.onNodeWithContentDescription("退出全景").performClick()
                advanceUntil("Returning to the shelf must animate through intermediate positions") { expansion() in 0.25f..0.75f }
                assertEquals(selectedId, album("demo-5").id)
                SmokeScreenshots.capture("16-motion-exit-middle", compose.activity).recycle()
                settle()
                assertEquals(0f, expansion(), .001f)
                assertEquals(sceneId, scene().id)
                assertEquals(selectedId, album("demo-5").id)
                assertTrue("Exiting must retain the album selected in panorama", album("demo-5").config[SemanticsProperties.Selected])
                compose.onNodeWithText("GLASS MEMORY").assertExists()
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
