package io.github.alicejump.rhine

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** CI pulls these app-scoped files before the emulator is shut down. */
internal object SmokeScreenshots {
    private const val FRAME_TIMEOUT_SECONDS = 5L

    fun capture(name: String, activity: Activity): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        awaitPresentedWindows(instrumentation, activity)
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

    private fun awaitPresentedWindows(instrumentation: Instrumentation, activity: Activity) {
        repeat(3) {
            val roots = visibleWindows(instrumentation, activity)
            check(roots.isNotEmpty()) { "There is no attached application window to capture" }
            if (!awaitCommittedFrames(instrumentation, roots)) {
                // A dialog can detach after its callback is registered. It cannot commit
                // another frame, so retry the current windows instead of reporting a hang.
                if (visibleWindows(instrumentation, activity).toSet() != roots.toSet()) return@repeat
                error("Timed out waiting for visible application window frames to commit")
            }
            // A frame-commit callback means submitted to the swap chain, not yet displayed.
            // Cross subsequent display frame boundaries before SurfaceFlinger takes the image.
            awaitDisplayFrames(instrumentation)
            if (visibleWindows(instrumentation, activity).toSet() == roots.toSet()) return
        }
        error("Application windows kept changing while preparing the screenshot")
    }

    private fun visibleWindows(instrumentation: Instrumentation, activity: Activity): List<View> {
        var roots = emptyList<View>()
        instrumentation.runOnMainSync {
            // ModalBottomSheet uses a separate dialog window. Waiting only for the Activity's
            // DecorView can leave the sheet's surface one frame behind the semantics tree.
            val candidates = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                else listOf(activity.window.decorView)
            roots = candidates.filter {
                it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.isShown &&
                    it.width > 0 && it.height > 0
            }.distinct()
        }
        return roots
    }

    private fun awaitCommittedFrames(instrumentation: Instrumentation, roots: List<View>): Boolean {
        val committed = CountDownLatch(roots.size)
        val cleanup = mutableListOf<() -> Unit>()
        try {
            instrumentation.runOnMainSync {
                for (root in roots) {
                    if (!root.isAttachedToWindow) {
                        // The caller checks the window set again and fences any replacement.
                        committed.countDown()
                        continue
                    }
                    val observer = root.viewTreeObserver
                    if (Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
                        val callback = Runnable { committed.countDown() }
                        observer.registerFrameCommitCallback(callback)
                        cleanup += { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
                    } else {
                        // API 26–28 and software windows have no frame-commit callback. Observe
                        // an actual draw, then use the same bounded display-frame fence below.
                        var posted = false
                        val listener = ViewTreeObserver.OnDrawListener {
                            if (!posted) {
                                posted = true
                                root.post { committed.countDown() }
                            }
                        }
                        observer.addOnDrawListener(listener)
                        cleanup += { if (observer.isAlive) observer.removeOnDrawListener(listener) }
                    }
                    root.invalidate()
                    root.postInvalidateOnAnimation()
                }
            }
            return committed.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            instrumentation.runOnMainSync { cleanup.forEach { it() } }
        }
    }

    private fun awaitDisplayFrames(instrumentation: Instrumentation) {
        val displayed = CountDownLatch(1)
        lateinit var choreographer: Choreographer
        lateinit var callback: Choreographer.FrameCallback
        instrumentation.runOnMainSync {
            choreographer = Choreographer.getInstance()
            var remaining = 2
            callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    remaining--
                    if (remaining == 0) displayed.countDown()
                    else choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
        try {
            check(displayed.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for display frames after the windows committed"
            }
        } finally {
            instrumentation.runOnMainSync { choreographer.removeFrameCallback(callback) }
        }
    }
}
