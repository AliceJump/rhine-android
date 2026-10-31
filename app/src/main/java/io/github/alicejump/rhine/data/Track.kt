package io.github.alicejump.rhine.data

import java.security.MessageDigest
import java.util.Locale

/** A track points to a document granted by the user, never to a guessed filesystem path. */
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkPath: String? = null,
    val albumKey: String,
    val trackNumber: Int = 0,
)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val tracks: List<Track>,
    val artworkPath: String? = null,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0) }
}

/** Keep compilation albums together, while keeping identically named folders/albums separate. */
fun groupAlbums(tracks: List<Track>): List<Album> = tracks
    .distinctBy { it.id }
    .groupBy { it.albumKey }
    .map { (key, entries) ->
        val ordered = entries.sortedWith(
            compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
        val artists = ordered.map { it.artist }.distinct()
        Album(
            id = key,
            title = ordered.first().album,
            artist = if (artists.size == 1) artists.first() else "多位艺人",
            tracks = ordered,
            artworkPath = ordered.firstNotNullOfOrNull { it.artworkPath },
        )
    }
    .sortedWith(compareBy<Album> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })

internal fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun albumIdentity(parentDocument: String, album: String): String =
    stableId(parentDocument + "\u0000" + album.trim().lowercase(Locale.ROOT))

internal fun parseTrackNumber(value: String?): Int = value
    ?.substringBefore('/')
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { it in 1..99_999 }
    ?: 0

internal fun isAudioDocument(name: String, mimeType: String?): Boolean {
    val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
    if (mime.startsWith("audio/")) return true
    // An audio-looking extension must not turn a declared video/image document into a track.
    if (mime.isNotEmpty() && mime != "application/octet-stream" && mime != "application/ogg") return false
    return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS
}

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "amr")
