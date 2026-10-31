package io.github.alicejump.rhine

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** CI pulls these app-scoped files before the emulator is shut down. */
internal object SmokeScreenshots {
    fun capture(name: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the resized window and its accessibility events to settle before capturing
        // SurfaceFlinger; a Compose-idle UI can still have an old rotation frame on display.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "The emulator did not return a screenshot"
        }
        val directory = File(
            checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "screenshots",
        ).apply { check(isDirectory || mkdirs()) }
        File(directory, "$name.png").outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        return screenshot
    }
}
