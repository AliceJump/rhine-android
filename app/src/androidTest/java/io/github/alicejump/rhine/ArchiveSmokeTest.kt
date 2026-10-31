package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual empty-library screen instead of pretending that demo albums can play. */
@RunWith(AndroidJUnit4::class)
class ArchiveSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun emptyLibraryNavigationAndAppearanceRemainUsable() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("DEMO ARCHIVE / 示例档案")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("DEMO ARCHIVE / 示例档案").assertIsDisplayed()
        compose.onNodeWithText("尚未播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放").assertIsNotEnabled()
        SmokeScreenshots.capture("01-archive-light", compose.activity).recycle()

        compose.onNodeWithText("02  曲目").performClick()
        compose.onNodeWithText("档案架还没有音乐").assertIsDisplayed()
        compose.onNodeWithText("0 TRACKS").assertIsDisplayed()
        SmokeScreenshots.capture("02-tracks-empty", compose.activity).recycle()

        compose.onNodeWithText("04  外观").performClick()
        compose.onNodeWithText("LIGHT").performClick()
        compose.waitForIdle()
        val light = SmokeScreenshots.capture("03-appearance-light", compose.activity)
        val lightLuminance = Color.luminance(light.getPixel(5, light.height / 2))
        light.recycle()

        compose.onNodeWithText("DARK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("你的聆听空间").assertIsDisplayed()
        val dark = SmokeScreenshots.capture("04-appearance-dark", compose.activity)
        val darkLuminance = Color.luminance(dark.getPixel(5, dark.height / 2))
        dark.recycle()
        assertTrue("The dark choice must visibly change the screen", darkLuminance < lightLuminance - .2f)

        compose.onNodeWithText("LIGHT").performClick()
        compose.onNodeWithText("01  档案").performClick()
        compose.onNodeWithText("DEMO ARCHIVE / 示例档案").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放").assertIsNotEnabled()

        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        // Configuration changes before the recreated Compose surface has finished resizing.
        // Wait for the actual measured layout so CI cannot capture a cropped portrait frame.
        compose.waitUntil(10_000) {
            val bounds = runCatching { compose.onRoot().fetchSemanticsNode().boundsInRoot }.getOrNull()
            bounds != null && bounds.width > bounds.height
        }
        compose.waitForIdle()
        compose.onNodeWithText("DEMO ARCHIVE / 示例档案").assertIsDisplayed()
        SmokeScreenshots.capture("05-archive-landscape", compose.activity).recycle()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
