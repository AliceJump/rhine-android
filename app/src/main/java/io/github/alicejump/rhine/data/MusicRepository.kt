package io.github.alicejump.rhine.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MusicLibraryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads only the document tree chosen in the system picker. All provider and disk access is on IO.
 * Scanning never commits the library. The caller commits its result with saveTracks together with
 * the selected tree/permission state in a non-cancellable transaction after a successful scan.
 */
class MusicRepository internal constructor(context: Context, private val artworkCacheLimitBytes: Long) {
    constructor(context: Context) : this(context, MAX_ARTWORK_CACHE_BYTES)

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val library = AtomicFile(File(app.filesDir, "music-library-v1.json"))
    private val artworkDirectory = File(app.cacheDir, "artwork")

    init {
        require(artworkCacheLimitBytes > 0) { "Artwork cache limit must be positive" }
    }

    /** Directory belonging to the last successfully loaded/committed library snapshot. */
    @Volatile
    var sourceTreeUri: Uri? = null
        private set

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val snapshot = readSnapshot()
            refreshCommittedArtwork(artworkState(), snapshot)
            if (snapshot == null) return@withLock emptyList()
            sourceTreeUri = snapshot.sourceTreeUri
            snapshot.tracks
        }
    }

    /** Caller holds cacheMutex, including when AtomicFile restores an interrupted write. */
    private fun readSnapshot(): LibrarySnapshot? {
        return try {
            // openRead must precede existence checks: older Android versions may only have a backup.
            val json = library.openRead().use { input ->
                if (input.channel.size() > MAX_LIBRARY_BYTES) return null
                input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            }
            if (json.optInt("version") != 1) return null
            val rows = json.optJSONArray("tracks") ?: return null
            if (rows.length() > MAX_TRACKS) return null
            val savedSource = if (json.isNull("sourceTreeUri")) null else {
                json.optString("sourceTreeUri").takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(it) }
            }
            val checkedArtwork = mutableMapOf<String, String?>()
            val tracks = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val uri = row.optString("uri")
                    val id = row.optString("id")
                    val albumKey = row.optString("albumKey")
                    if (uri.isBlank() || id.isBlank() || albumKey.isBlank()) continue
                    if (Uri.parse(uri).scheme != ContentResolver.SCHEME_CONTENT) continue
                    val artwork = row.optString("artworkPath")
                    if (artwork !in checkedArtwork) checkedArtwork[artwork] = safeCachedArtwork(artwork)
                    add(
                        Track(
                            id = id,
                            uri = uri,
                            title = row.optString("title").ifBlank { "未命名曲目" },
                            artist = row.optString("artist").ifBlank { "未知艺人" },
                            album = row.optString("album").ifBlank { "未知专辑" },
                            durationMs = row.optLong("durationMs").coerceAtLeast(0),
                            artworkPath = checkedArtwork[artwork],
                            albumKey = albumKey,
                            trackNumber = row.optInt("trackNumber").coerceAtLeast(0),
                        ),
                    )
                }
            }.distinctBy { it.id }
            LibrarySnapshot(tracks, savedSource)
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    suspend fun saveTracks(tracks: List<Track>, sourceTreeUri: Uri? = null) = withContext(Dispatchers.IO) {
        require(tracks.size <= MAX_TRACKS) { "Too many tracks" }
        require(sourceTreeUri == null ||
            (sourceTreeUri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(sourceTreeUri))) {
            "Expected a document tree source"
        }
        val rows = JSONArray()
        tracks.forEach { track ->
            rows.put(
                JSONObject()
                    .put("id", track.id)
                    .put("uri", track.uri)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("durationMs", track.durationMs)
                    .put("artworkPath", track.artworkPath ?: "")
                    .put("albumKey", track.albumKey)
                    .put("trackNumber", track.trackNumber),
            )
        }
        val bytes = JSONObject()
            .put("version", 1)
            .put("sourceTreeUri", sourceTreeUri?.toString() ?: JSONObject.NULL)
            .put("tracks", rows)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LIBRARY_BYTES) throw MusicLibraryException("曲库信息过大，请选择较小的音乐文件夹。")
        currentCoroutineContext().ensureActive()
        cacheMutex.withLock {
            currentCoroutineContext().ensureActive()
            val state = artworkState()
            val committedArtwork = tracks.mapNotNull { it.artworkPath?.let(::safeCachedArtwork) }.toSet()
            val stream = library.startWrite()
            try {
                stream.write(bytes)
                currentCoroutineContext().ensureActive()
                library.finishWrite(stream)
                this@MusicRepository.sourceTreeUri = sourceTreeUri
                state.committed = committedArtwork
            } catch (error: Throwable) {
                library.failWrite(stream)
                throw error
            }
            // A concurrent scan can still own other candidate artwork, so only release a fully
            // committed candidate set. All protection changes and eviction share the snapshot lock.
            val candidate = state.candidates[this@MusicRepository]
            if (candidate?.complete == true && candidate.paths.all { it in committedArtwork }) {
                state.candidates.remove(this@MusicRepository)
            }
            // Cache maintenance cannot turn an already committed snapshot into a save failure.
            try {
                pruneArtwork(state, artworkCacheLimitBytes)
            } catch (_: Exception) {
                // Retry bounded cache maintenance on the next admission.
            }
        }
    }

    /**
     * Returns a candidate library without replacing the saved one. onProgress runs on the IO
     * thread with the number of imported tracks. Call saveTracks only after accepting this result.
     */
    suspend fun scanTree(uri: Uri, onProgress: (Int) -> Unit = {}): List<Track> {
        var candidate: CandidateArtwork? = null
        var candidateState: ArtworkState? = null
        try {
            return withContext(Dispatchers.IO) {
                scanMutex.withLock {
                    if (uri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(uri)) {
                        throw MusicLibraryException("请通过系统选择器选择一个音乐文件夹。")
                    }
                    cacheMutex.withLock {
                        val state = artworkState()
                        // A new repository may scan before loadTracks; persisted covers must already be
                        // protected. Other repositories may still have results awaiting a later commit.
                        refreshCommittedArtwork(state, readSnapshot())
                        candidate = CandidateArtwork()
                        candidateState = state
                        state.candidates[this@MusicRepository] = checkNotNull(candidate)
                        state.bytes = artworkDirectory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
                    }
                    try {
                        val treeId = DocumentsContract.getTreeDocumentId(uri)
                        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
                        val rootName = readRootName(rootUri)
                        val folders = ArrayDeque<Folder>()
                        folders.add(Folder(treeId, rootName))
                        val visitedFolders = mutableSetOf<String>()
                        val visitedTracks = mutableSetOf<String>()
                        val tracks = mutableListOf<Track>()
                        val albumArtwork = mutableMapOf<String, String>()
                        var visitedEntries = 0
                        var lastProgressAt = 0L
                        fun reportProgress(force: Boolean = false) {
                            val now = System.nanoTime()
                            if (force || now - lastProgressAt >= 100_000_000L) {
                                lastProgressAt = now
                                onProgress(tracks.size)
                            }
                        }
                        reportProgress(force = true)

                        while (folders.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val folder = folders.removeFirst()
                            if (!visitedFolders.add(folder.id)) continue
                            if (visitedFolders.size > MAX_FOLDERS) {
                                throw MusicLibraryException("文件夹数量超过 $MAX_FOLDERS 个，请选择范围更小的音乐目录。")
                            }
                            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, folder.id)
                            val children = readChildren(childrenUri, MAX_ENTRIES - visitedEntries)
                            visitedEntries += children.size
                            val folderUri = DocumentsContract.buildDocumentUriUsingTree(uri, folder.id)
                            val cover = children.firstOrNull { child ->
                                !child.directory && child.name.lowercase(Locale.ROOT) in COVER_NAMES
                            }
                            var folderArtworkBytes: ByteArray? = null
                            var checkedFolderArtwork = false

                            for (child in children) {
                                currentCoroutineContext().ensureActive()
                                if (child.directory) {
                                    folders.addLast(Folder(child.id, child.name))
                                    continue
                                }
                                if (!isAudioDocument(child.name, child.mime)) continue
                                val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, child.id)
                                if (!visitedTracks.add(documentUri.toString())) continue
                                if (tracks.size >= MAX_TRACKS) {
                                    throw MusicLibraryException("曲目超过 $MAX_TRACKS 首，请分成更小的音乐文件夹。")
                                }
                                val result = readMetadata(documentUri, child, folder, folderUri, albumArtwork) ?: continue
                                var track = result
                                if (track.artworkPath == null && cover != null) {
                                    val coverUri = DocumentsContract.buildDocumentUriUsingTree(uri, cover.id)
                                    if (!checkedFolderArtwork) {
                                        folderArtworkBytes = readArtworkBytes(coverUri)
                                        checkedFolderArtwork = true
                                    }
                                    folderArtworkBytes?.let { bytes ->
                                        // Document IDs are only unique inside one provider; include its URI/authority.
                                        val cacheKey = stableId("$coverUri|${cover.modified}|${cover.size}")
                                        cacheArtwork(bytes, cacheKey)?.let { path ->
                                            albumArtwork[track.albumKey] = path
                                            track = track.copy(artworkPath = path)
                                        }
                                    }
                                }
                                tracks += track
                                reportProgress()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        // A later track may contain the album cover that earlier tracks did not.
                        val complete = tracks.map { track ->
                            if (track.artworkPath == null) track.copy(artworkPath = albumArtwork[track.albumKey]) else track
                        }
                        cacheMutex.withLock { candidate?.complete = true }
                        reportProgress(force = true)
                        complete
                    } catch (error: SecurityException) {
                        throw MusicLibraryException("音乐文件夹的访问权限已失效，请重新选择文件夹。", error)
                    }
                }
            }
        } catch (error: Throwable) {
            // Also handles cancellation while dispatching a successful IO result back to Main.
            // A later scan on the same repository must not have its newer candidate lease removed.
            withContext(NonCancellable + Dispatchers.IO) {
                cacheMutex.withLock {
                    candidateState?.let { state ->
                        if (state.candidates[this@MusicRepository] === candidate) {
                            state.candidates.remove(this@MusicRepository)
                        }
                    }
                }
            }
            throw error
        }
    }

    private suspend fun readRootName(uri: Uri): String = query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { cursor ->
        if (!cursor.moveToFirst()) throw MusicLibraryException("所选文件夹已不存在，请重新选择。")
        cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "音乐文件夹"
    }

    private suspend fun readChildren(uri: Uri, remaining: Int): List<Document> = query(
        uri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
    ) { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                if (size >= remaining) throw MusicLibraryException("目录内容过多，请选择范围更小的音乐文件夹。")
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: id.substringAfterLast('/')
                val mime = cursor.getString(2).orEmpty()
                add(
                    Document(
                        id = id,
                        name = name,
                        mime = mime,
                        modified = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        size = if (cursor.isNull(4)) -1 else cursor.getLong(4),
                    ),
                )
            }
        }
    }

    /** Cancellation closes the provider's query rather than waiting for a remote document provider. */
    private suspend fun <T> query(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val result = resolver.query(uri, projection, null, null, null, signal)?.use(read)
                    ?: throw MusicLibraryException("无法读取音乐文件夹，请确认目录仍然可用。")
                continuation.resume(result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }

    private suspend fun readMetadata(
        uri: Uri,
        document: Document,
        folder: Folder,
        folderUri: Uri,
        albumArtwork: MutableMap<String, String>,
    ): Track? {
        if (document.size == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            fun tag(key: Int): String? = retriever.extractMetadata(key)?.trim()?.take(512)?.takeIf { it.isNotEmpty() }
            val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: document.name.substringBeforeLast('.')
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "未知艺人"
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: folder.name
            val albumKey = albumIdentity(folderUri.toString(), album)
            var artworkPath = albumArtwork[albumKey]
            if (artworkPath == null) {
                val bytes = retriever.embeddedPicture
                if (bytes != null && bytes.size <= MAX_ARTWORK_INPUT_BYTES) {
                    val cacheKey = stableId("$uri|${document.modified}|${document.size}")
                    artworkPath = cacheArtwork(bytes, cacheKey)
                    artworkPath?.let { albumArtwork[albumKey] = it }
                }
            }
            Track(
                id = stableId(uri.toString()),
                uri = uri.toString(),
                title = title.ifBlank { "未命名曲目" },
                artist = artist,
                album = album.ifBlank { "未知专辑" },
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                artworkPath = artworkPath,
                albumKey = albumKey,
                trackNumber = parseTrackNumber(tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            // A corrupt/unsupported track must not abort an otherwise usable folder import.
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // A failed metadata decoder may already have released its native context.
            }
        }
    }

    private fun readArtworkBytes(uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_ARTWORK_INPUT_BYTES) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: IOException) {
        null
    }

    private suspend fun cacheArtwork(bytes: ByteArray, key: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_INPUT_BYTES) return null
        val file = File(artworkDirectory, "$key.jpg")
        cacheMutex.withLock { cachedArtwork(file, artworkState()) }?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 32_768 || bounds.outHeight > 32_768) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ARTWORK_EDGE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var scaled = bitmap
        return try {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest > ARTWORK_EDGE) {
                val scale = ARTWORK_EDGE.toFloat() / largest
                scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val encoded = ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) throw IOException("Artwork encoding failed")
                output.toByteArray()
            }
            if (encoded.size > artworkCacheLimitBytes) return null
            cacheMutex.withLock {
                val state = artworkState()
                cachedArtwork(file, state)?.let { return@withLock it }
                if (!artworkDirectory.isDirectory && !artworkDirectory.mkdirs()) return@withLock null
                val availableForExisting = artworkCacheLimitBytes - encoded.size
                if (state.bytes < 0 || state.bytes > availableForExisting) {
                    // Reserve room before writing. Referenced files are never eviction candidates:
                    // when they consume the budget, this new cover simply stays uncached.
                    if (!pruneArtwork(state, availableForExisting)) return@withLock null
                }
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(encoded)
                    atomic.finishWrite(output)
                } catch (error: IOException) {
                    atomic.failWrite(output)
                    throw error
                }
                state.bytes += file.length()
                cachedArtwork(file, state)
            }
        } catch (_: IOException) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun safeCachedArtwork(path: String): String? {
        if (path.isBlank()) return null
        return try {
            val file = File(path).canonicalFile
            file.takeIf { it.parentFile == artworkDirectory.canonicalFile && it.isFile }?.absolutePath
        } catch (_: IOException) {
            null
        }
    }

    /** All artwork state, including weak owner entries, is accessed while holding cacheMutex. */
    private fun artworkState(): ArtworkState = artworkStates.getOrPut(artworkDirectory.canonicalPath) { ArtworkState() }

    private fun refreshCommittedArtwork(state: ArtworkState, snapshot: LibrarySnapshot?) {
        if (snapshot != null) {
            state.committed = snapshot.tracks.mapNotNull { it.artworkPath }.toSet()
        } else if (!library.baseFile.exists() && !File("${library.baseFile.path}.bak").exists()) {
            state.committed = emptySet()
        } else if (state.committed.isEmpty()) {
            // If an existing snapshot is unreadable and no references are known in this process,
            // fail closed: only a subsequent successful load/save may unprotect these files.
            state.committed = artworkDirectory.listFiles()?.filter { it.isFile }?.map { it.canonicalPath }?.toSet().orEmpty()
        }
    }

    private fun cachedArtwork(file: File, state: ArtworkState): String? {
        if (!file.isFile || file.length() == 0L) return null
        val path = file.canonicalPath
        state.candidates.getOrPut(this@MusicRepository) { CandidateArtwork() }.paths.add(path)
        file.setLastModified(System.currentTimeMillis())
        return path
    }

    private fun pruneArtwork(state: ArtworkState, targetBytes: Long): Boolean {
        val protected = state.committed + state.candidates.values.flatMap { it.paths }
        val files = artworkDirectory.listFiles()?.filter { it.isFile }.orEmpty()
        var bytes = files.sumOf { it.length() }
        files.filter { it.canonicalPath !in protected }.sortedBy { it.lastModified() }.forEach { file ->
            if (bytes > targetBytes) {
                val length = file.length()
                if (file.delete()) bytes -= length
            }
        }
        state.bytes = bytes
        return bytes <= targetBytes
    }

    private data class LibrarySnapshot(val tracks: List<Track>, val sourceTreeUri: Uri?)

    private class CandidateArtwork(val paths: MutableSet<String> = mutableSetOf(), var complete: Boolean = false)

    private class ArtworkState {
        var committed: Set<String> = emptySet()
        val candidates = WeakHashMap<MusicRepository, CandidateArtwork>()
        var bytes = -1L
    }

    private data class Folder(val id: String, val name: String)

    private data class Document(val id: String, val name: String, val mime: String, val modified: Long, val size: Long) {
        val directory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    companion object {
        // Shared mutexes also protect against two repository instances during Activity recreation.
        private val cacheMutex = Mutex()
        private val scanMutex = Mutex()
        private val artworkStates = mutableMapOf<String, ArtworkState>()
        private const val MAX_TRACKS = 20_000
        private const val MAX_FOLDERS = 2_000
        private const val MAX_ENTRIES = 100_000
        private const val MAX_LIBRARY_BYTES = 20 * 1024 * 1024
        private const val MAX_ARTWORK_INPUT_BYTES = 8 * 1024 * 1024
        private const val MAX_ARTWORK_CACHE_BYTES = 64L * 1024 * 1024
        private const val ARTWORK_EDGE = 512
        private val COVER_NAMES = setOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png", "front.jpg")
    }
}
