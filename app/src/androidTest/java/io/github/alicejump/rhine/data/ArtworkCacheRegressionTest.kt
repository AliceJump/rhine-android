package io.github.alicejump.rhine.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Real SAF scans with a two-cover budget, isolated from the app's saved library and artwork. */
@RunWith(AndroidJUnit4::class)
class ArtworkCacheRegressionTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var artworkDirectory: File
    private lateinit var snapshot: File
    private lateinit var committedCover: File
    private lateinit var committedBytes: ByteArray
    private lateinit var jpeg: ByteArray
    private var pngSize = 0
    private var budget = 0L

    @Before fun createIsolatedLibrary() {
        directory = Files.createTempDirectory(baseContext.cacheDir.toPath(), "artwork-regression-").toFile().canonicalFile
        context = object : ContextWrapper(baseContext) {
            private val isolatedFiles = File(directory, "files").apply { check(mkdirs()) }
            private val isolatedCache = File(directory, "cache").apply { check(mkdirs()) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
            override fun getCacheDir(): File = isolatedCache
        }
        artworkDirectory = File(context.cacheDir, "artwork").apply { check(mkdirs()) }
        val coverUri = DocumentsContract.buildDocumentUriUsingTree(tree("artwork"), "artwork/a/cover.png")
        val png = requireNotNull(baseContext.contentResolver.openInputStream(coverUri)).use { it.readBytes() }
        pngSize = png.size
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try {
            jpeg = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        budget = 2L * jpeg.size
        committedCover = File(artworkDirectory, "committed.jpg").apply { writeBytes(jpeg) }
        val source = tree("previous")
        val row = JSONObject()
            .put("id", "committed-track")
            .put("uri", DocumentsContract.buildDocumentUriUsingTree(source, "previous/tone.wav").toString())
            .put("title", "Committed track")
            .put("artist", "Fixture artist")
            .put("album", "Committed album")
            .put("durationMs", 12_000L)
            .put("artworkPath", committedCover.absolutePath)
            .put("albumKey", "committed-album")
            .put("trackNumber", 1)
        committedBytes = JSONObject()
            .put("version", 1)
            .put("sourceTreeUri", source.toString())
            .put("tracks", JSONArray().put(row))
            .toString().toByteArray(Charsets.UTF_8)
        snapshot = File(context.filesDir, "music-library-v1.json").apply { writeBytes(committedBytes) }
    }

    @After fun removeIsolatedLibrary() {
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test fun coldSnapshotAndCacheHitRemainProtectedUnderPressure() = runBlocking {
        val coverUri = DocumentsContract.buildDocumentUriUsingTree(tree("artwork"), "artwork/a/cover.png")
        val cachedA = File(artworkDirectory, "${stableId("$coverUri|1|$pngSize")}.jpg").apply {
            writeBytes(jpeg)
            setLastModified(1L)
        }
        val orphan = File(artworkDirectory, "orphan.jpg").apply {
            writeBytes(jpeg)
            setLastModified(2L)
        }
        val repository = MusicRepository(context, budget)

        // Intentionally do not call loadTracks: the committed references must come from disk.
        val tracks = repository.scanTree(tree("artwork"))

        assertEquals(3, tracks.size)
        assertEquals(cachedA.canonicalPath, tracks.forFolder("a").artworkPath)
        assertTrue("A cached hit must become a protected candidate", cachedA.isFile)
        assertNull("A full protected cache must refuse a new cover", tracks.forFolder("b").artworkPath)
        assertNull(tracks.forFolder("c").artworkPath)
        assertFalse("Only the unreferenced cover may be evicted", orphan.exists())
        assertNull("Scanning must not load a new source into repository state", repository.sourceTreeUri)
        assertCommittedLibraryIntact()
        assertWithinBudget()
    }

    @Test fun cancellationPreservesCommittedCoverAndReleasesItsCandidates() = runBlocking {
        val repository = MusicRepository(context, budget)
        val scan = launch {
            val scanJob = coroutineContext.job
            repository.scanTree(tree("artwork")) { count ->
                if (count >= 3) scanJob.cancel(CancellationException("Cancel the scan after cache pressure"))
            }
        }
        scan.join()

        assertTrue("The real scan must reach cancellation", scan.isCancelled)
        assertCommittedLibraryIntact()
        assertWithinBudget()

        // The cancelled scan's cover may now be evicted, leaving room for this different cover.
        val retried = MusicRepository(context, budget).scanTree(tree("artwork/b"))
        assertEquals(1, retried.size)
        assertNotNull("Cancelled candidate references must be released", retried.single().artworkPath)
        assertTrue(File(requireNotNull(retried.single().artworkPath)).isFile)
        assertNull(repository.sourceTreeUri) // Keep the cancelled repository strongly reachable.
        assertCommittedLibraryIntact()
        assertWithinBudget()
    }

    @Test fun secondRepositoryCannotEvictAnUncommittedFirstScan() = runBlocking {
        val firstRepository = MusicRepository(context, budget)
        val firstTracks = firstRepository.scanTree(tree("artwork/a"))
        val firstCover = File(requireNotNull(firstTracks.single().artworkPath))
        assertTrue(firstCover.isFile)

        val secondRepository = MusicRepository(context, budget)
        val secondTracks = secondRepository.scanTree(tree("artwork/b"))

        assertEquals(1, secondTracks.size)
        assertNull("Another repository's pending candidate must remain protected", secondTracks.single().artworkPath)
        assertTrue("The first result must not contain a dangling artwork path", firstCover.isFile)
        assertNull(firstRepository.sourceTreeUri) // Keep its owner alive until after the second scan.
        assertCommittedLibraryIntact()
        assertWithinBudget()

        // Commit transfers protection to the new snapshot. The old cover becomes reclaimable,
        // while the saved candidate must remain protected when another import needs that space.
        firstRepository.saveTracks(firstTracks, tree("artwork/a"))
        val thirdTracks = MusicRepository(context, budget).scanTree(tree("artwork/b"))
        assertNotNull(thirdTracks.single().artworkPath)
        assertTrue(firstCover.isFile)
        assertFalse("A superseded committed cover may now be evicted", committedCover.exists())
        assertEquals(firstTracks, MusicRepository(context, budget).loadTracks())
        assertWithinBudget()
    }

    private fun List<Track>.forFolder(folder: String): Track = single {
        DocumentsContract.getDocumentId(Uri.parse(it.uri)) == "artwork/$folder/tone.wav"
    }

    private fun assertCommittedLibraryIntact() {
        assertArrayEquals("Scanning must preserve the exact committed snapshot", committedBytes, snapshot.readBytes())
        assertTrue("Committed artwork must survive failed or incomplete imports", committedCover.isFile)
        assertArrayEquals(jpeg, committedCover.readBytes())
    }

    private fun assertWithinBudget() {
        val bytes = artworkDirectory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        assertTrue("Artwork cache uses $bytes bytes, exceeding its $budget-byte budget", bytes <= budget)
    }

    private fun tree(id: String): Uri =
        DocumentsContract.buildTreeDocumentUri("${baseContext.packageName}.testdocuments", id)
}
