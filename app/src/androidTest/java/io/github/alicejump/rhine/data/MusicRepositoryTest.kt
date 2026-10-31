package io.github.alicejump.rhine.data

import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Exercises the real ContentResolver, metadata decoder and atomic snapshot on a device. */
@RunWith(AndroidJUnit4::class)
class MusicRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
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

    @Test fun scanDecodesRealAudioSkipsCorruptFileAndDoesNotCommit() = runBlocking {
        val repository = MusicRepository(context)
        val tracks = repository.scanTree(tree("music"))

        assertEquals("Only the real WAV may enter the library", 1, tracks.size)
        assertTrue("Duration must come from the real decoder", tracks.single().durationMs in 11_500L..12_500L)
        assertEquals("content", Uri.parse(tracks.single().uri).scheme)
        assertNull("Scanning must not change the source tree", repository.sourceTreeUri)
        assertTrue("Scanning must not persist a candidate library", MusicRepository(context).loadTracks().isEmpty())
    }

    @Test fun committedTracksAndSourceTreeAreRestoredFromTheSameSnapshot() = runBlocking {
        val source = tree("music")
        val repository = MusicRepository(context)
        val tracks = repository.scanTree(source)
        repository.saveTracks(tracks, source)

        val restored = MusicRepository(context)
        assertEquals(tracks, restored.loadTracks())
        assertEquals(source, restored.sourceTreeUri)
        assertEquals(source, repository.sourceTreeUri)
    }

    @Test fun cancellationAndProviderFailureKeepThePreviousSnapshot() = runBlocking {
        val repository = MusicRepository(context)
        val oldSource = tree("previous")
        val original = repository.scanTree(oldSource)
        repository.saveTracks(original, oldSource)
        val committedBytes = snapshot.readFully().toList()

        val scan = launch {
            val scanJob = coroutineContext.job
            repository.scanTree(tree("music")) { count ->
                if (count > 0) scanJob.cancel(CancellationException("Cancel after decoding the first real track"))
            }
        }
        scan.join()
        assertTrue("The scan must actually be cancelled", scan.isCancelled)
        assertEquals(committedBytes, snapshot.readFully().toList())
        assertEquals(oldSource, repository.sourceTreeUri)

        try {
            repository.scanTree(tree("failure"))
            fail("The fixture provider must deny this scan")
        } catch (_: MusicLibraryException) {
            // Production code must translate an inaccessible document provider into an import error.
        }
        val restored = MusicRepository(context)
        assertEquals(original, restored.loadTracks())
        assertEquals(oldSource, restored.sourceTreeUri)
        assertEquals(committedBytes, snapshot.readFully().toList())
    }

    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri("${context.packageName}.testdocuments", id)
}
