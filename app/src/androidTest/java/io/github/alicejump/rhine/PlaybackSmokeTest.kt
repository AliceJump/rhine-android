package io.github.alicejump.rhine

import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import io.github.alicejump.rhine.playback.PlaybackService
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Plays a real debug-only PCM fixture through the production MediaSessionService. */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun realPlaybackCanPauseSeekAndContinueAfterActivityMovesToBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controllerFuture: ListenableFuture<MediaController> = onMain {
                MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                ).buildAsync()
            }
            var controller: MediaController? = null
            try {
                val player = controllerFuture.get(10, TimeUnit.SECONDS).also { controller = it }
                val tone = MediaItem.Builder()
                    .setMediaId("instrumentation-pcm-tone")
                    .setUri(Uri.parse("android.resource://${context.packageName}/${R.raw.rhine_test_tone}"))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Rhine smoke-test tone")
                            .setArtist("Generated PCM fixture")
                            .build(),
                    )
                    .build()
                onMain {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItem(tone)
                    player.prepare()
                    player.play()
                }
                awaitPlayer(player, "PCM must decode and start playing") {
                    it.playbackState == Player.STATE_READY && it.isPlaying && it.currentPosition >= 350
                }
                assertTrue("The fixture duration should be decoded", onMain { player.duration } in 11_500L..12_500L)

                onMain { player.pause() }
                awaitPlayer(player, "Pause must reach the service") { !it.isPlaying && !it.playWhenReady }
                val pausedPosition = onMain { player.currentPosition }
                SystemClock.sleep(350)
                assertTrue("Paused audio must not continue advancing", abs(onMain { player.currentPosition } - pausedPosition) < 150)

                onMain { player.seekTo(4_000) }
                awaitPlayer(player, "Seek must move the real playback position") {
                    it.currentPosition in 3_700L..4_500L && !it.isPlaying
                }
                onMain { player.play() }
                awaitPlayer(player, "Playback must resume") { it.isPlaying }

                scenario.onActivity { activity ->
                    assertTrue("The activity should be moved behind the launcher", activity.moveTaskToBack(true))
                }
                awaitCondition("The activity must actually leave the foreground") {
                    scenario.state != Lifecycle.State.RESUMED
                }
                val backgroundPosition = onMain { player.currentPosition }
                awaitPlayer(player, "Audio must keep progressing with the activity in the background") {
                    it.isPlaying && it.currentPosition >= backgroundPosition + 1_000
                }
                assertTrue("The activity must remain in the background", scenario.state != Lifecycle.State.RESUMED)
            } finally {
                onMain {
                    controller?.let { player ->
                        player.stop()
                        player.clearMediaItems()
                    }
                    MediaController.releaseFuture(controllerFuture)
                }
            }
        }
    }

    private fun awaitPlayer(player: MediaController, message: String, predicate: (MediaController) -> Boolean) {
        awaitCondition(message) {
            onMain {
                player.playerError?.let { error -> throw AssertionError("$message: ${error.errorCodeName}", error) }
                predicate(player)
            }
        }
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
