package io.github.alicejump.rhine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    @Test fun compilationTracksStayTogetherAndSortByTrackNumber() {
        val tracks = listOf(
            track("b", artist = "Guest", number = 2),
            track("a", artist = "Artist", number = 1),
            track("c", artist = "Artist", number = 0),
        )
        val albums = groupAlbums(tracks)
        assertEquals(1, albums.size)
        assertEquals("多位艺人", albums.single().artist)
        assertEquals(listOf("a", "b", "c"), albums.single().tracks.map { it.id })
    }

    @Test fun sameAlbumNameInDifferentDirectoriesDoesNotMerge() {
        val keyA = albumIdentity("content://provider/folder-a", "Greatest Hits")
        val keyB = albumIdentity("content://provider/folder-b", "Greatest Hits")
        assertNotEquals(keyA, keyB)
        assertEquals(2, groupAlbums(listOf(track("a", key = keyA), track("b", key = keyB))).size)
    }

    @Test fun duplicateDocumentsAreNotCountedTwice() {
        val first = track("same", number = 1)
        val album = groupAlbums(listOf(first, first.copy(title = "Duplicate"))).single()
        assertEquals(1, album.tracks.size)
        assertEquals(120_000L, album.durationMs)
    }

    @Test fun slashSeparatedTrackNumbersAndMissingTagsAreHandled() {
        assertEquals(3, parseTrackNumber("03/12"))
        assertEquals(0, parseTrackNumber(null))
        assertEquals(0, parseTrackNumber("-1"))
        assertEquals(0, parseTrackNumber("1.5"))
        assertEquals(0, parseTrackNumber("999999999999999"))
    }

    @Test fun audioCandidateDetectionRespectsDeclaredNonAudioMime() {
        assertTrue(isAudioDocument("TRACK.FLAC", "application/octet-stream"))
        assertTrue(isAudioDocument("opaque", "audio/mpeg"))
        assertFalse(isAudioDocument("cover.mp3", "image/jpeg"))
        assertFalse(isAudioDocument("movie.mp3", "video/mp4"))
        assertFalse(isAudioDocument("notes.txt", "text/plain"))
    }

    @Test fun artworkMayComeFromALaterTrack() {
        val album = groupAlbums(listOf(track("a"), track("b").copy(artworkPath = "/cache/cover.jpg"))).single()
        assertEquals("/cache/cover.jpg", album.artworkPath)
    }

    private fun track(id: String, artist: String = "Artist", number: Int = 0, key: String = "album") = Track(
        id = id,
        uri = "content://provider/$id",
        title = id,
        artist = artist,
        album = "Album",
        durationMs = 120_000,
        albumKey = key,
        trackNumber = number,
    )
}
