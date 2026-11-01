package io.github.alicejump.rhine

import android.content.ComponentName
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alicejump.rhine.data.MusicRepository
import io.github.alicejump.rhine.data.Track
import io.github.alicejump.rhine.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cold-loaded library and actual global controls exercise the production playback service. */
@RunWith(AndroidJUnit4::class)
class GlobalPlaybackSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val snapshot = AtomicFile(File(context.filesDir, "music-library-v1.json"))
    private var previousSnapshot: ByteArray? = null

    @Before fun isolateSnapshot() {
        previousSnapshot = try { snapshot.readFully() } catch (_: IOException) { null }
        snapshot.delete()
    }

    @After fun restoreSnapshot() {
        snapshot.delete()
        previousSnapshot?.let { bytes ->
            val output = snapshot.startWrite()
            try {
                output.write(bytes)
                snapshot.finishWrite(output)
            } catch (error: Exception) {
                snapshot.failWrite(output)
                throw error
            }
        }
    }

    @Test
    fun bothGlobalControlsPlayRealAudioWithoutCreatingASelection() {
        val uri = DocumentsContract.buildDocumentUri("${context.packageName}.testdocuments", "music/tone.wav").toString()
        val tracks = listOf(
            Track("global-first", uri, "First PCM fixture", "Generated signal", "A fixture", 12_000, albumKey = "global-a"),
            Track("global-second", uri, "Second PCM fixture", "Generated signal", "B fixture", 12_000, albumKey = "global-b"),
        )
        // Persist before Activity creation: the real ViewModel loads a nonempty cold-start library.
        runBlocking { MusicRepository(context).saveTracks(tracks) }
        withLoadedPlayer { model, player ->
            assertEquals(2, onMain { model.tracks.size })
            val shelf = ArchiveSceneProbe(compose)
            shelf.awaitScene()
            shelf.assertSelected("")
            assertNull(onMain { model.selectedAlbumId })
            compose.onNodeWithContentDescription("播放").assertIsEnabled().performClick()
            awaitPlaying(player, "global-first")
            assertNull("Global playback must not select or lift the fallback album", onMain { model.selectedAlbumId })
            shelf.assertSelected("")

            compose.onNodeWithContentDescription("暂停").performClick()
            compose.waitUntil(10_000) { onMain { !player.isPlaying && !model.playback.playing } }
            onMain { player.seekTo(4_000) }
            compose.waitUntil(10_000) { onMain { player.currentPosition in 3_800L..4_500L } }
            compose.onNodeWithContentDescription("播放").performClick()
            awaitPlaying(player, "global-first", minimumPosition = 4_100)
            assertNull("Resuming the existing queue must preserve empty selection", onMain { model.selectedAlbumId })

            emptyQueue(model, player)
            compose.onNodeWithText("03  播放").performClick()
            compose.onNodeWithContentDescription("播放").assertIsEnabled()
            compose.onNodeWithContentDescription("开始播放").performScrollTo().assertIsEnabled().performClick()
            awaitPlaying(player, "global-first")
            assertNull("The full player must also preserve empty selection", onMain { model.selectedAlbumId })
            compose.onNodeWithText("01  档案").performClick()
            shelf.awaitScene()
            shelf.assertSelected("")

            emptyQueue(model, player)
            onMain { model.selectAlbum(model.albums.last()) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("播放").performClick()
            awaitPlaying(player, "global-second")
            assertEquals("An explicit selection takes priority over the first album", "global-b", onMain { model.selectedAlbumId })

            onMain { player.pause(); model.clearAlbumSelection() }
            compose.waitUntil(10_000) { onMain { !player.isPlaying && !model.playback.playing } }
            compose.onNodeWithContentDescription("播放").performClick()
            awaitPlaying(player, "global-second")
            assertNull("Clearing selection must not replace an existing queue with the first album", onMain { model.selectedAlbumId })

            onMain { model.playAlbum(model.albums.first()) }
            awaitPlaying(player, "global-first")
            assertEquals("Explicit album playback must still select that album", "global-a", onMain { model.selectedAlbumId })
        }
    }

    @Test
    fun emptyLibraryKeepsBothGlobalPlayControlsDisabled() {
        withLoadedPlayer { model, player ->
            assertTrue(onMain { model.tracks.isEmpty() })
            compose.onNodeWithContentDescription("播放").assertIsNotEnabled()
            compose.onNodeWithText("03  播放").performClick()
            compose.onNodeWithContentDescription("播放").assertIsNotEnabled()
            compose.onNodeWithContentDescription("开始播放").performScrollTo().assertIsNotEnabled()
            assertNull(onMain { player.currentMediaItem })
            assertNull(onMain { model.selectedAlbumId })
        }
    }

    private fun withLoadedPlayer(block: (RhineViewModel, MediaController) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val future = onMain {
                MediaController.Builder(context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            var player: MediaController? = null
            try {
                val connected = future.get(10, TimeUnit.SECONDS).also { player = it }
                onMain { connected.repeatMode = Player.REPEAT_MODE_OFF; connected.stop(); connected.clearMediaItems() }
                lateinit var model: RhineViewModel
                scenario.onActivity { model = ViewModelProvider(it)[RhineViewModel::class.java] }
                compose.waitUntil(10_000) {
                    onMain { !model.loading && model.playback.connected && model.playback.trackId == null }
                }
                compose.waitForIdle()
                block(model, connected)
            } finally {
                onMain {
                    player?.let { it.stop(); it.clearMediaItems() }
                    MediaController.releaseFuture(future)
                }
            }
        }
    }

    private fun emptyQueue(model: RhineViewModel, player: MediaController) {
        onMain { player.stop(); player.clearMediaItems() }
        compose.waitUntil(10_000) { onMain { model.playback.trackId == null && !model.playback.playing } }
        compose.waitForIdle()
    }

    private fun awaitPlaying(player: MediaController, id: String, minimumPosition: Long = 350) {
        compose.waitUntil(10_000) {
            onMain {
                player.playerError?.let { throw AssertionError("PCM playback failed: ${it.errorCodeName}", it) }
                player.currentMediaItem?.mediaId == id && player.playbackState == Player.STATE_READY &&
                    player.isPlaying && player.currentPosition >= minimumPosition
            }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
