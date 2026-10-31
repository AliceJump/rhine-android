package io.github.alicejump.rhine

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** CI pulls these app-scoped files before the emulator is shut down. */
internal object SmokeScreenshots {
    fun capture(name: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
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
