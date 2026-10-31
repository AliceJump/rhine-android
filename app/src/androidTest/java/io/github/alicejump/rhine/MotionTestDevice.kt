package io.github.alicejump.rhine

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** These settings belong to the isolated test emulator and are restored after each motion test. */
internal object MotionTestDevice {
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    fun <T> withAnimations(block: () -> T): T {
        val keys = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
        val original = keys.associateWith { shell("settings get global $it").trim() }
        try {
            keys.forEach { shell("settings put global $it 1") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return block()
        } finally {
            original.forEach { (key, value) ->
                if (value == "null" || value.isEmpty()) shell("settings delete global $key")
                else {
                    check(value.toFloatOrNull() != null) { "Unexpected animation scale value for $key" }
                    shell("settings put global $key $value")
                }
            }
        }
    }

    fun awaitFrames(count: Int) {
        require(count > 0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = CountDownLatch(1)
        lateinit var choreographer: Choreographer
        lateinit var callback: Choreographer.FrameCallback
        instrumentation.runOnMainSync {
            choreographer = Choreographer.getInstance()
            var remaining = count
            callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) completed.countDown()
                    else choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
        try {
            check(completed.await(maxOf(5L, count.toLong() / 5), TimeUnit.SECONDS)) { "Display frames stopped during motion capture" }
        } finally {
            instrumentation.runOnMainSync { choreographer.removeFrameCallback(callback) }
        }
    }

    fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            awaitFrames(2)
        }
        throw AssertionError(message)
    }
}
