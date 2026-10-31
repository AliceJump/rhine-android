package io.github.alicejump.rhine.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.alicejump.rhine.data.Track
import java.io.File

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setArtworkUri(artworkPath?.let { Uri.fromFile(File(it)) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()
