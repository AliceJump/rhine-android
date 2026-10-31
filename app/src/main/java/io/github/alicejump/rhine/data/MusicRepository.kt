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
import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MusicLibraryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads only the document tree chosen in the system picker. All provider and disk access is on IO.
 * Scanning never commits the library. The caller commits its result with saveTracks together with
 * the selected tree/permission state in a non-cancellable transaction after a successful scan.
 */
class MusicRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val library = AtomicFile(File(app.filesDir, "music-library-v1.json"))
    private val artworkDirectory = File(app.cacheDir, "artwork")
    private var artworkCacheBytes = -1L

    /** Directory belonging to the last successfully loaded/committed library snapshot. */
    @Volatile
    var sourceTreeUri: Uri? = null
        private set

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            try {
                // openRead must run before checking the base file: older Android versions may
                // have only AtomicFile's backup after a process is killed in the middle of a write.
                val json = library.openRead().use { input ->
                    if (input.channel.size() > MAX_LIBRARY_BYTES) return@withLock emptyList()
                    input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                }
                if (json.optInt("version") != 1) return@withLock emptyList()
                val rows = json.optJSONArray("tracks") ?: return@withLock emptyList()
                if (rows.length() > MAX_TRACKS) return@withLock emptyList()
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
                sourceTreeUri = savedSource
                tracks
            } catch (_: JSONException) {
                // The next complete scan can recreate a corrupt/discontinued cache.
                emptyList()
            } catch (_: IOException) {
                emptyList()
            }
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
            val stream = library.startWrite()
            try {
                stream.write(bytes)
                currentCoroutineContext().ensureActive()
                library.finishWrite(stream)
                this@MusicRepository.sourceTreeUri = sourceTreeUri
            } catch (error: Throwable) {
                library.failWrite(stream)
                throw error
            }
        }
        // The library has committed. Cache maintenance must never make callers roll back a grant
        // or report that saving failed after the new document URIs were already persisted.
        try {
            pruneArtwork(tracks.mapNotNull { it.artworkPath }.toSet())
        } catch (_: Exception) {
            // Artwork is expendable and bounded again on the next import.
        }
    }

    /**
     * Returns a candidate library without replacing the saved one. onProgress runs on the IO
     * thread with the number of imported tracks. Call saveTracks only after accepting this result.
     */
    suspend fun scanTree(uri: Uri, onProgress: (Int) -> Unit = {}): List<Track> = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(uri)) {
                throw MusicLibraryException("请通过系统选择器选择一个音乐文件夹。")
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
                reportProgress(force = true)
                complete
            } catch (error: SecurityException) {
                throw MusicLibraryException("音乐文件夹的访问权限已失效，请重新选择文件夹。", error)
            }
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

    private fun readMetadata(
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

    private fun cacheArtwork(bytes: ByteArray, key: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_INPUT_BYTES) return null
        if (!artworkDirectory.isDirectory && !artworkDirectory.mkdirs()) return null
        val file = File(artworkDirectory, "$key.jpg")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file.absolutePath
        }
        if (artworkCacheBytes < 0) artworkCacheBytes = artworkDirectory.listFiles()?.sumOf { it.length() } ?: 0
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
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) throw IOException("Artwork encoding failed")
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
            artworkCacheBytes += file.length()
            // Prune during a long import as well, so a cancelled scan cannot leave an unbounded cache.
            if (artworkCacheBytes > MAX_ARTWORK_CACHE_BYTES) {
                pruneArtwork(emptySet(), MAX_ARTWORK_CACHE_BYTES * 3 / 4)
            }
            file.absolutePath
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

    private fun pruneArtwork(referenced: Set<String>, targetBytes: Long = MAX_ARTWORK_CACHE_BYTES) {
        val files = artworkDirectory.listFiles()?.filter { it.isFile } ?: return
        var bytes = files.sumOf { it.length() }
        files.sortedWith(compareBy<File> { it.absolutePath in referenced }.thenBy { it.lastModified() }).forEach { file ->
            if (bytes > targetBytes) {
                val length = file.length()
                if (file.delete()) bytes -= length
            }
        }
        artworkCacheBytes = bytes
    }

    private data class Folder(val id: String, val name: String)

    private data class Document(val id: String, val name: String, val mime: String, val modified: Long, val size: Long) {
        val directory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    companion object {
        // Shared mutexes also protect against two repository instances during Activity recreation.
        private val cacheMutex = Mutex()
        private val scanMutex = Mutex()
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
