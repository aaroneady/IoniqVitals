package com.ioniqvitals.car

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

object MediaSessionManager {
    private var session: MediaSessionCompat? = null

    fun getSession(context: Context): MediaSessionCompat {
        val current = session
        if (current != null) return current

        val newSession = MediaSessionCompat(context, "IoniqVitalsMediaSession").apply {
            // Start INACTIVE. We're a browse-only dashboard, not a player — we should only hold an
            // active media session while OBD is connected (the live dashboard the user is viewing).
            // Staying inactive otherwise lets the head unit keep/return the media slot to the real
            // player (YouTube / YouTube Music) instead of stranding it on us after disconnect.
            isActive = false
            // Inert callback: the dashboard tiles are display-only. AA media-browse items are
            // always selectable (no API to disable the tap), so when one is tapped we swallow the
            // play command and re-assert STOPPED — no playback starts and the host doesn't switch
            // to a "now playing" screen, so a tap effectively does nothing.
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                    session?.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_STOPPED))
                }
                override fun onPlay() {
                    session?.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_STOPPED))
                }
            })
            setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_STOPPED))
        }
        session = newSession
        return newSession
    }

    /**
     * Activate/deactivate the media session. Active only while OBD is connected; deactivating on
     * disconnect releases the head unit's media slot back to the previous app (YouTube / YTM).
     */
    fun setActive(active: Boolean) {
        val s = session ?: return
        if (s.isActive == active) return
        s.isActive = active
        s.setPlaybackState(
            buildPlaybackState(if (active) PlaybackStateCompat.STATE_STOPPED else PlaybackStateCompat.STATE_NONE),
        )
    }

    /** Flip the session to PLAYING so the media (album-art) card surfaces. Media-only mode only. */
    fun setPlaying() {
        session?.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_PLAYING))
    }

    private fun buildPlaybackState(state: Int): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setState(state, 0, 1.0f)
            .setActions(0)
            .build()

    fun updateMetadata(title: String?, subtitle: String?, art: Bitmap? = null) {
        val builder = MediaMetadataCompat.Builder()
        
        // Use a single space instead of null/empty to try and "clear" the text 
        // while signaling a metadata change to the head unit.
        val t = if (title.isNullOrBlank()) " " else title
        val s = if (subtitle.isNullOrBlank()) " " else subtitle

        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, t)
        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s)
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, " ")
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, t)
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s)
        
        // Duration -1 hints at a live stream
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
        
        // Add a timestamp to force the head unit to recognize this as a fresh metadata update
        builder.putLong("com.google.android.gms.car.media.METADATA_KEY_TIMESTAMP", System.currentTimeMillis())

        if (art != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        }
        
        session?.setMetadata(builder.build())
    }

    fun release() {
        session?.release()
        session = null
    }
}
