package io.github.alicejump.rhine.playback

import android.app.PendingIntent
import android.content.ContentResolver
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Owns playback independently of the screen, including focus, headphone unplug and media controls. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val builder = MediaSession.Builder(this, player).setCallback(SessionCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val isApplication = controller.uid == Process.myUid()
            // System/notification/Bluetooth clients can operate playback. They cannot inject a URI
            // for the service to open using the application's persisted document permissions.
            if (!isApplication && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            if (!isApplication) {
                playerCommands.remove(Player.COMMAND_SET_MEDIA_ITEM)
                playerCommands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands.build())
                .setAvailableSessionCommands(
                    if (isApplication) MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS else SessionCommands.EMPTY,
                )
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFailedFuture(SecurityException("Only the application can change the queue"))
            }
            if (mediaItems.any { item ->
                    val uri = item.localConfiguration?.uri
                    uri == null || when (uri.scheme) {
                        ContentResolver.SCHEME_CONTENT -> uri.authority.isNullOrBlank()
                        ContentResolver.SCHEME_ANDROID_RESOURCE -> uri.authority != packageName
                        else -> true
                    }
                }
            ) {
                return Futures.immediateFailedFuture(IllegalArgumentException("Expected a granted audio document"))
            }
            return Futures.immediateFuture(mediaItems)
        }
    }
}
