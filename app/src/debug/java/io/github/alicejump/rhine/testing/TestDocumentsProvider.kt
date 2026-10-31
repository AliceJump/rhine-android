package io.github.alicejump.rhine.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.github.alicejump.rhine.R
import java.io.File
import java.io.FileNotFoundException

/** Read-only, debug-only SAF fixture. It is deliberately not registered with the system picker. */
class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var fixtureDirectory: File

    override fun onCreate(): Boolean {
        val app = requireNotNull(context)
        fixtureDirectory = File(app.cacheDir, "test-documents").apply { mkdirs() }
        app.resources.openRawResource(R.raw.rhine_test_tone).use { input ->
            File(fixtureDirectory, "tone.wav").outputStream().use { output -> input.copyTo(output) }
        }
        File(fixtureDirectory, "broken.wav").writeText("This is not an audio file.")
        val cover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70, 110, 130)) }
        try {
            File(fixtureDirectory, "cover.png").outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            cover.recycle()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = cursor(
        projection,
        arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS),
        listOf(mapOf(Root.COLUMN_ROOT_ID to "fixture", Root.COLUMN_DOCUMENT_ID to "music", Root.COLUMN_TITLE to "Test audio", Root.COLUMN_FLAGS to 0)),
    )

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection, DOCUMENT_COLUMNS, listOf(document(documentId)))

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (parentDocumentId == "failure") throw SecurityException("Fixture document permission was revoked")
        if (parentDocumentId !in DIRECTORY_IDS) throw FileNotFoundException(parentDocumentId)
        val children = when {
            parentDocumentId == "artwork" -> listOf("artwork/a", "artwork/b", "artwork/c")
            parentDocumentId.startsWith("artwork/") -> listOf("$parentDocumentId/tone.wav", "$parentDocumentId/cover.png")
            else -> listOf("$parentDocumentId/tone.wav", "$parentDocumentId/broken.wav")
        }
        return cursor(projection, DOCUMENT_COLUMNS, children.map(::document))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("The test provider is read-only")
        signal?.throwIfCanceled()
        document(documentId) // Validate the fixed fixture ID before mapping it to a private file.
        if (documentId in DIRECTORY_IDS) throw FileNotFoundException("Not a file")
        return ParcelFileDescriptor.open(File(fixtureDirectory, documentId.substringAfterLast('/')), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun document(id: String): Map<String, Any?> {
        if (id in DIRECTORY_IDS) return mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to "Fixture $id",
            Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
            Document.COLUMN_FLAGS to 0,
        )
        val root = id.substringBeforeLast('/', "")
        val name = id.substringAfterLast('/')
        val fileNames = if (root.startsWith("artwork/")) setOf("tone.wav", "cover.png") else setOf("tone.wav", "broken.wav")
        if (root !in DIRECTORY_IDS || name !in fileNames) throw FileNotFoundException(id)
        return mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to name,
            Document.COLUMN_MIME_TYPE to if (name == "cover.png") "image/png" else "audio/wav",
            Document.COLUMN_SIZE to File(fixtureDirectory, name).length(),
            Document.COLUMN_LAST_MODIFIED to 1L,
            Document.COLUMN_FLAGS to 0,
        )
    }

    private fun cursor(
        requested: Array<out String>?,
        fallback: Array<String>,
        rows: List<Map<String, Any?>>,
    ): Cursor {
        val columns = requested?.toList()?.toTypedArray() ?: fallback
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) } }
    }

    companion object {
        private val DIRECTORY_IDS = setOf("music", "previous", "failure", "artwork", "artwork/a", "artwork/b", "artwork/c")
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
