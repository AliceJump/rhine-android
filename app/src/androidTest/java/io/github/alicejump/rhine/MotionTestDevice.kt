package io.github.alicejump.rhine

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
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

    /** Native touch events keep recording on the application's real animation clock. */
    fun drag(startX: Float, startY: Float, endX: Float, endY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        var released = false
        send(MotionEvent.ACTION_DOWN, startX, startY)
        try {
            repeat(12) { index ->
                awaitFrames(1)
                val fraction = (index + 1) / 12f
                send(MotionEvent.ACTION_MOVE, startX + (endX - startX) * fraction, startY + (endY - startY) * fraction)
            }
            send(MotionEvent.ACTION_UP, endX, endY)
            released = true
        } finally {
            if (!released) send(MotionEvent.ACTION_CANCEL, endX, endY)
        }
    }

    fun pinch(centerX: Float, centerY: Float, initialSpan: Float, scale: Float) {
        val downTime = SystemClock.uptimeMillis()
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(2) { MotionEvent.PointerCoords().apply {
            pressure = 1f
            size = 1f
        } }
        fun send(action: Int, count: Int, span: Float) {
            coordinates[0].x = centerX - span
            coordinates[1].x = centerX + span
            coordinates.forEach { it.y = centerY }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        var released = false
        send(MotionEvent.ACTION_DOWN, 1, initialSpan)
        try {
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, initialSpan)
            repeat(18) { index ->
                awaitFrames(1)
                val span = initialSpan * (1f + (scale - 1f) * (index + 1) / 18f)
                send(MotionEvent.ACTION_MOVE, 2, span)
            }
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, initialSpan * scale)
            send(MotionEvent.ACTION_UP, 1, initialSpan * scale)
            released = true
        } finally {
            if (!released) send(MotionEvent.ACTION_CANCEL, 2, initialSpan)
        }
    }
}
