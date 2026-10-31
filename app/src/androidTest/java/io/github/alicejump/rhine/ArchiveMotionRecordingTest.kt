package io.github.alicejump.rhine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** No Compose test clock: screenrecord must see the application's real-time animation. */
@RunWith(AndroidJUnit4::class)
class ArchiveMotionRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun recordPhoneAndTabletMotion() {
        MotionTestDevice.withAnimations {
            recordDevice(tablet = false)
            recordDevice(tablet = true)
        }
    }

    private fun recordDevice(tablet: Boolean) {
        val originalSize = Regex("Override size: ([0-9]+x[0-9]+)").find(MotionTestDevice.shell("wm size"))?.groupValues?.get(1)
        val originalDensity = Regex("Override density: ([0-9]+)").find(MotionTestDevice.shell("wm density"))?.groupValues?.get(1)
        try {
            if (tablet) {
                MotionTestDevice.shell("wm size 1600x2560")
                MotionTestDevice.shell("wm density 320")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val orientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val expected = if (tablet) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                scenario.onActivity { it.requestedOrientation = orientation }
                MotionTestDevice.await("The recording window must reach its requested orientation") {
                    var ready = false
                    scenario.onActivity {
                        ready = it.resources.configuration.orientation == expected &&
                            (if (tablet) it.window.decorView.width > it.window.decorView.height else it.window.decorView.height > it.window.decorView.width)
                    }
                    ready
                }
                awaitAction("全景浏览")
                val label = if (tablet) "tablet-landscape-motion" else "phone-portrait-motion"
                val size = if (tablet) "1280x800" else "720x1280"
                ScreenRecording.start(label, size).use {
                    MotionTestDevice.awaitFrames(24)
                    // Begin with every case lowered, then explicitly select A.
                    click("QUIET ORBIT")
                    MotionTestDevice.awaitFrames(42)
                    click("全景浏览")
                    MotionTestDevice.awaitFrames(54)
                    val browsing = panoramaBounds()
                    MotionTestDevice.drag(
                        browsing.exactCenterX(), browsing.top + browsing.height() * .72f,
                        browsing.exactCenterX() + browsing.width() * .10f, browsing.top + browsing.height() * .66f,
                    )
                    MotionTestDevice.awaitFrames(30)
                    click("总览") // Camera reset must leave A raised.
                    MotionTestDevice.awaitFrames(42)
                    click("GLASS MEMORY") // B rises while A lowers.
                    MotionTestDevice.awaitFrames(54)
                    awaitAction("全景专辑详情")
                    click("回到选中") // Public camera action: it must not itself exit panorama.
                    MotionTestDevice.awaitFrames(36)
                    awaitAction("退出全景")
                    val centered = panoramaBounds()
                    val reach = minOf(centered.width() * .40f, centered.height() * .30f)
                    MotionTestDevice.pinch(centered.exactCenterX(), centered.exactCenterY(), reach / 3.2f, 3.2f)
                    awaitAction("全景浏览")
                    MotionTestDevice.awaitFrames(42)
                    click("全景浏览") // B must still be raised when re-entering.
                    MotionTestDevice.awaitFrames(54)
                    click("总览")
                    MotionTestDevice.awaitFrames(36)
                    click("取消选择")
                    MotionTestDevice.awaitFrames(54)
                    click("退出全景")
                    awaitAction("全景浏览")
                    MotionTestDevice.awaitFrames(42)
                }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally {
            if (tablet) {
                MotionTestDevice.shell("wm size ${originalSize ?: "reset"}")
                MotionTestDevice.shell("wm density ${originalDensity ?: "reset"}")
            }
        }
    }

    private fun awaitAction(label: String) {
        try {
            MotionTestDevice.await("Visible action '$label' was not available during motion recording") {
                val node = action(label)
                (node != null).also { node?.recycle() }
            }
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}\nActive accessibility window:\n${windowSummary()}", error)
        }
    }

    private fun click(label: String) {
        awaitAction(label)
        val node = checkNotNull(action(label)) { "Action '$label' disappeared before it could be used" }
        try {
            assertTrue("The application must accept action '$label'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            node.recycle()
        }
    }

    private fun panoramaBounds(): Rect {
        var node: AccessibilityNodeInfo? = null
        MotionTestDevice.await("The visible panorama canvas must expose its real screen bounds") {
            node = find(automation.rootInActiveWindow) {
                it.isVisibleToUser && it.contentDescription?.toString()?.contains("唱片全景") == true
            }
            node != null
        }
        return try {
            Rect().also { checkNotNull(node).getBoundsInScreen(it) }
        } finally {
            node?.recycle()
        }
    }

    private fun action(label: String): AccessibilityNodeInfo? {
        var node = find(automation.rootInActiveWindow) { node ->
            node.isVisibleToUser && node.isEnabled &&
            (node.contentDescription?.toString()?.contains(label) == true || node.text?.toString() == label)
        } ?: return null
        // Compose may expose the Text separately from the button that owns ACTION_CLICK.
        repeat(8) {
            if (node.isVisibleToUser && node.isEnabled &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
            val parent = node.parent
            node.recycle()
            node = parent ?: return null
        }
        node.recycle()
        return null
    }

    private fun windowSummary(): String = buildString {
        var count = 0
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            try {
                if (count++ >= 80 || depth > 12) return
                append("  ".repeat(depth))
                append("${node.className} text=${node.text} desc=${node.contentDescription}")
                append(" visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable}")
                appendLine(" actions=${node.actionList.map { it.id to it.label }}")
                for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
            } finally {
                node.recycle()
            }
        }
        appendNode(automation.rootInActiveWindow, 0)
        if (count == 0) append("<no active window>")
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        var keep = false
        try {
            if (predicate(node)) {
                keep = true
                return node
            }
            for (index in 0 until node.childCount) {
                find(node.getChild(index), predicate)?.let { return it }
            }
            return null
        } finally {
            if (!keep) node.recycle()
        }
    }
}

/** Runs only this test's bounded screenrecord process and finalizes its MP4 with SIGINT. */
private class ScreenRecording(
    private val file: File,
    private val shellPath: String,
    private val script: File,
    private val pid: Int,
    private val reader: BufferedReader,
    private val executor: ExecutorService,
    private val output: Future<String>,
) : Closeable {
    override fun close() {
        try {
            if (!output.isDone) {
                // Do not signal a recycled PID or someone else's recorder.
                val arguments = MotionTestDevice.shell("cat /proc/$pid/cmdline").split('\u0000')
                if (shellPath in arguments && arguments.firstOrNull()?.endsWith("screenrecord") == true) {
                    MotionTestDevice.shell("kill -2 $pid")
                }
            }
            File(file.parentFile, "${file.nameWithoutExtension}.log").writeText(output.get(10, TimeUnit.SECONDS))
            // The recorder runs as shell. Copy its exact bytes through the shell's pipe
            // into a file created by this app, rather than relying on emulated-storage ownership.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat $shellPath")).use { input ->
                file.outputStream().use { target -> input.copyTo(target) }
            }
            check(file.isFile && file.length() > 1_024) { "The screen recorder did not produce a video: ${file.name}" }
            val metadata = MediaMetadataRetriever()
            try {
                FileInputStream(file).use { input ->
                    metadata.setDataSource(input.fd)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    check(duration > 2_000) { "The recording is missing its motion sequence: ${file.name}" }
                    check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "The recording has no video stream" }
                }
            } finally {
                metadata.release()
            }
        } finally {
            reader.close()
            executor.shutdownNow()
            script.delete()
            MotionTestDevice.shell("rm -f $shellPath")
        }
    }

    companion object {
        fun start(name: String, size: String): ScreenRecording {
            require(name.matches(Regex("[a-z-]+")) && size.matches(Regex("[0-9]+x[0-9]+")))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "recordings")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "$name.mp4")
            check(!file.exists() || file.delete()) { "A previous recording could not be replaced" }
            val shellPath = "/data/local/tmp/rhine-motion-${SystemClock.uptimeMillis()}-$name.mp4"
            val script = File(checkNotNull(context.externalCacheDir), "$name.sh")
            check(script.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
            script.writeText(
                "if [ \"${'$'}1\" = ready ]; then\n" +
                    "  if test -s $shellPath; then echo ready; fi\n  exit\nfi\n" +
                    "echo ${'$'}${'$'}\nexec screenrecord --size $size --bit-rate 4000000 --time-limit 45 $shellPath 2>&1\n",
            )
            val reader = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"),
            ).bufferedReader()
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "motion-recording-output").apply { isDaemon = true } }
            try {
                val pid = executor.submit<String> { checkNotNull(reader.readLine()) }.get(5, TimeUnit.SECONDS).trim().toInt()
                check(pid > 1)
                val output = executor.submit<String> { reader.readText() }
                val recording = ScreenRecording(file, shellPath, script, pid, reader, executor, output)
                try {
                    MotionTestDevice.await("The screen recorder did not start") {
                        check(!output.isDone) { "The screen recorder exited before recording the scene: ${output.get()}" }
                        MotionTestDevice.shell("sh ${script.absolutePath} ready").trim() == "ready"
                    }
                    return recording
                } catch (error: Throwable) {
                    runCatching { recording.close() }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            } catch (error: Throwable) {
                reader.close()
                executor.shutdownNow()
                script.delete()
                MotionTestDevice.shell("rm -f $shellPath")
                throw error
            }
        }
    }
}
