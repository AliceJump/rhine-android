package io.github.alicejump.rhine

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.alicejump.rhine.data.Album
import io.github.alicejump.rhine.data.MusicRepository
import io.github.alicejump.rhine.data.Track
import io.github.alicejump.rhine.data.groupAlbums
import io.github.alicejump.rhine.playback.PlaybackService
import io.github.alicejump.rhine.playback.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUi(
    val trackId: String? = null,
    val title: String = "尚未播放",
    val artist: String = "选择专辑，开始聆听",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val connected: Boolean = false,
    val repeat: Boolean = false,
)

class RhineViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val preferences = application.getSharedPreferences("rhine", 0)
    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var selectedAlbumId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var scanning by mutableStateOf(false)
        private set
    var scannedCount by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var darkMode by mutableStateOf(preferences.getBoolean("dark", false))
        private set
    var accentIndex by mutableStateOf(preferences.getInt("accent", 0).coerceIn(0, 2))
        private set
    var playback by mutableStateOf(PlaybackUi())
        private set
    private var importJob: Job? = null
    private var scanGeneration = 0L
    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java)),
    ).buildAsync()

    val lastFolder: Uri?
        get() = repository.sourceTreeUri

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshPlayback()
        override fun onPlayerError(error: PlaybackException) {
            message = "无法播放此文件，请检查文件是否存在、格式是否受支持，或重新选择音乐文件夹。"
            refreshPlayback()
        }
    }

    init {
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get().also { it.addListener(listener) }
                refreshPlayback()
            } catch (_: Exception) {
                message = "播放服务连接失败，请关闭应用后重试。"
            }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            try {
                setLibrary(repository.loadTracks())
                val previous = lastFolder
                if (previous != null && !hasPermission(previous)) {
                    message = "音乐文件夹授权已失效，请重新选择文件夹。"
                }
            } catch (_: Exception) {
                message = "未能读取已保存的音乐库，请重新选择音乐文件夹。"
            } finally {
                loading = false
            }
        }
        viewModelScope.launch {
            while (true) {
                if (controller?.isPlaying == true) refreshPlayback()
                delay(500)
            }
        }
    }

    private fun hasPermission(uri: Uri): Boolean = getApplication<Application>().contentResolver
        .persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun importFolder(uri: Uri) {
        if (scanning || loading) return
        val resolver = getApplication<Application>().contentResolver
        val alreadyGranted = hasPermission(uri)
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            message = "无法保留这个目录的读取权限，请通过系统选择器重新选择。"
            return
        }
        scanning = true
        val generation = ++scanGeneration
        scannedCount = 0
        message = null
        val previousFolder = lastFolder
        importJob = viewModelScope.launch {
            var committed = false
            try {
                val imported = repository.scanTree(uri) { count ->
                    viewModelScope.launch {
                        if (scanning && generation == scanGeneration) scannedCount = count
                    }
                }
                currentCoroutineContext().ensureActive()
                // Once saving begins, finish the cache/grant transition as one commit. Cancelling
                // while scanning must never leave a new cache with the new directory grant revoked.
                withContext(NonCancellable) {
                    repository.saveTracks(imported, uri)
                    committed = true
                    setLibrary(imported)
                    controller?.let { player ->
                        if (player.isConnected) {
                            player.stop()
                            player.clearMediaItems()
                        }
                    }
                    if (previousFolder != null && previousFolder != uri) {
                        try { resolver.releasePersistableUriPermission(previousFolder, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        catch (_: SecurityException) { /* A revoked old grant needs no cleanup. */ }
                    }
                }
                message = if (imported.isEmpty()) "这个目录没有找到可读取的音频。可以换一个文件夹。"
                else "已导入 ${imported.size} 首音乐 · ${albums.size} 张专辑"
            } catch (cancelled: CancellationException) {
                if (!committed) message = "已取消扫描，保留原音乐库。"
                throw cancelled
            } catch (failure: Exception) {
                message = "扫描未完成，原音乐库已保留。${failure.message?.take(100).orEmpty()}"
            } finally {
                if (!committed && !alreadyGranted) {
                    try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { /* Provider may have revoked its grant. */ }
                }
                scanning = false
                if (generation == scanGeneration) scanGeneration++
            }
        }
    }

    fun rescan() { lastFolder?.let(::importFolder) }
    fun cancelScan() { importJob?.cancel() }
    fun dismissMessage() { message = null }

    private fun setLibrary(items: List<Track>) {
        tracks = items
        albums = groupAlbums(items)
        selectedAlbumId = selectedAlbumId?.takeIf { id -> albums.any { it.id == id } }
    }

    fun selectAlbum(album: Album) { selectedAlbumId = album.id }

    fun clearAlbumSelection() { selectedAlbumId = null }

    fun playAlbum(album: Album, index: Int = 0) =
        startAlbumPlayback(album, index, updateSelection = true)

    /** Global controls can start a queue without changing the shelf's visual selection. */
    private fun startAlbumPlayback(album: Album, index: Int, updateSelection: Boolean) {
        if (album.tracks.isEmpty()) {
            message = "这是示例档案，导入音乐后即可播放。"
            return
        }
        val player = controller ?: run { message = "播放服务尚未连接，请稍后重试。"; return }
        if (updateSelection) selectedAlbumId = album.id
        player.setMediaItems(album.tracks.map { it.toMediaItem() }, index.coerceIn(album.tracks.indices), 0)
        player.prepare()
        player.play()
        refreshPlayback()
    }

    fun playTrack(track: Track) {
        val album = albums.firstOrNull { it.id == track.albumKey } ?: return
        playAlbum(album, album.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    fun togglePlayback() {
        val player = controller ?: return
        if (player.currentMediaItem == null) {
            (albums.firstOrNull { it.id == selectedAlbumId } ?: albums.firstOrNull())
                ?.let { startAlbumPlayback(it, 0, updateSelection = false) }
        } else if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        refreshPlayback()
    }

    fun next() { controller?.seekToNextMediaItem(); refreshPlayback() }
    fun previous() {
        controller?.let { if (it.currentPosition > 3_000) it.seekTo(0) else it.seekToPreviousMediaItem() }
        refreshPlayback()
    }
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceIn(0, playback.durationMs.coerceAtLeast(0)))
        refreshPlayback()
    }
    fun toggleRepeat() {
        controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }
        refreshPlayback()
    }
    fun setDark(value: Boolean) { darkMode = value; preferences.edit().putBoolean("dark", value).apply() }
    fun setAccent(value: Int) { accentIndex = value.coerceIn(0, 2); preferences.edit().putInt("accent", accentIndex).apply() }

    private fun refreshPlayback() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        playback = PlaybackUi(
            trackId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString() ?: "尚未播放",
            artist = metadata.artist?.toString() ?: "选择专辑，开始聆听",
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            connected = true,
            repeat = player.repeatMode != Player.REPEAT_MODE_OFF,
        )
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
