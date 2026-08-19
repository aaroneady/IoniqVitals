package com.ioniqvitals.car

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import com.ioniqvitals.R
import com.ioniqvitals.obd.ConnectionState
import com.ioniqvitals.obd.ObdDataRepository
import com.ioniqvitals.obd.ObdPreferences
import com.ioniqvitals.obd.ObdReaderService
import com.ioniqvitals.obd.ObdSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * [MediaBrowserServiceCompat] that renders the dashboard as a **grid of media-browse tiles**.
 *
 * The Car App Library [GridTemplate] can't be used for a sideloaded app on the 2022 DHU — when
 * you open a category.MEDIA app, Android Auto runs its OWN MediaCarAppService and browses this
 * service; it never launches our CarAppService (proven via logcat). So we render three tiles
 * (Front lights, Rear lights, and a consolidated stats tile holding 12V/Temp/SOH/SOC) as the
 * browse content, hinting CONTENT_STYLE = GRID so the host lays them out as a tile grid with
 * icon + title + subtitle.
 *
 * Android Auto binds this service itself (we never call it). It invokes [onGetRoot] then
 * [onLoadChildren]; we publish the current tiles and call [notifyChildrenChanged] whenever the
 * OBD data changes so the grid refreshes in place.
 */
class IoniqVitalsMediaBrowserService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latest = ObdSnapshot()
    // Attempt the auto-connect at most once per bind (per Android Auto session) so a manual
    // disconnect isn't immediately undone and a failed connect doesn't loop.
    private var autoConnectTried = false

    override fun onCreate() {
        super.onCreate()
        sessionToken = MediaSessionManager.getSession(this).sessionToken

        // Do NOT mark the session playing: an actively-playing session makes the host show the
        // now-playing card instead of the browse grid. We want the browse grid, so stay stopped.
        scope.launch {
            ObdDataRepository.dataFlow.collectLatest { data ->
                // Only hold an ACTIVE media session while OBD is connected (we're the live
                // dashboard the user is viewing). On disconnect, deactivate so the head unit
                // releases the media slot back to the previous app (YouTube / YouTube Music).
                val connected = data.connectionState == ConnectionState.CONNECTED ||
                    data.connectionState == ConnectionState.READING
                MediaSessionManager.setActive(connected)

                val changed = renderKey(data) != renderKey(latest)
                latest = data
                // Refresh the grid only when a displayed value actually changed (avoids churn).
                if (changed) notifyChildrenChanged(ROOT_ID)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        MediaSessionManager.release()
        super.onDestroy()
    }

    /**
     * When Android Auto binds this service (i.e. an AA session has started), auto-connect to the
     * last-used OBD adapter. Gated to the AA/projection client so the phone's media-resumption
     * probe (which also binds media browsers, e.g. at boot) never triggers an OBD connection.
     */
    private fun maybeAutoConnectObd(clientPackageName: String) {
        if (autoConnectTried || !isAndroidAuto(clientPackageName)) return
        // Reconnect whatever was last selected (the "last known OBD") — including the mock adapter
        // if that's what was used last.
        val address = ObdPreferences.getSelectedDeviceAddress(this) ?: return
        when (latest.connectionState) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.READING -> return
            else -> {}
        }
        autoConnectTried = true
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ObdReaderService::class.java).apply {
                    action = ObdReaderService.ACTION_CONNECT
                    putExtra(ObdReaderService.EXTRA_DEVICE_ADDRESS, address)
                },
            )
            Log.i(TAG, "Android Auto started (client=$clientPackageName) — auto-connecting OBD to $address")
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException on stricter OEMs — fall back to manual.
            Log.w(TAG, "Auto-connect failed; user can connect manually", e)
            autoConnectTried = false
        }
    }

    private fun isAndroidAuto(pkg: String): Boolean =
        pkg.contains("projection.gearhead") || pkg.contains("android.car") || pkg.contains("automotive")

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        maybeAutoConnectObd(clientPackageName)

        // Only 3 tiles now (2 lights + 1 stats), so the standard (larger) grid fits in one row
        // without scrolling and keeps the stats text readable.
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(buildTiles(latest))
    }

    /** Builds the six dashboard tiles (or a single status tile while not connected). */
    private fun buildTiles(s: ObdSnapshot): MutableList<MediaBrowserCompat.MediaItem> {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (s.connectionState != ConnectionState.CONNECTED && s.connectionState != ConnectionState.READING) {
            items.add(tile("status", "Waiting for OBD", "Open the phone app and Connect", R.mipmap.ic_launcher))
            return items
        }

        // Light tiles: icon only (the on/off graphic conveys state — no text). Served via a content
        // URI whose `res` param differs between the on/off drawables, so the URL itself changes when
        // the light toggles. Android Auto keys its icon cache by URL, so a changed URL forces a
        // re-fetch — that's what makes the tile actually update on state change (an inline
        // setIconBitmap doesn't: the mediaId/title are unchanged, so the host treats the re-queried
        // item as identical and never re-renders the embedded bitmap).
        val frontRes = if (s.headlightsOn == true) {
            R.drawable.ioniq_5_front_lights_on_comic_book_style
        } else {
            R.drawable.ioniq_5_front_lights_off_comic_book_style
        }
        items.add(tile("front", "", "", frontRes))

        val rearRes = if (s.brakeLightsOn == true) {
            R.drawable.ioniq_5_rear_lights_on_comic_book_style
        } else {
            R.drawable.ioniq_5_rear_lights_off_comic_book_style
        }
        items.add(tile("rear", "", "", rearRes))

        // Third tile: the four numeric readouts as left-justified text lines (no icons). Every line
        // is always shown so the layout stays stable; a value that isn't ready yet renders as a
        // placeholder ("--") instead of dropping the whole line.
        val statLines = listOf(
            "12V: " + (s.auxSocPercent?.let { "%.1f%%".format(it) } ?: PENDING),
            "Temp: " + (s.coolantTempCelsius?.let { "%.0f°C / %.0f°F".format(it, it * 9f / 5f + 32f) } ?: PENDING),
            "SOH: " + (s.sohPercent?.let { "%.1f%%".format(it) } ?: PENDING),
            "SOC: " + (s.socDisplayPercent?.let { "%.1f%%".format(it) } ?: PENDING),
        )
        items.add(statsTile("stats", statLines))
        return items
    }

    /** The consolidated stats tile: left-justified text lines rendered by [TileImageProvider]. */
    private fun statsTile(id: String, lines: List<String>): MediaBrowserCompat.MediaItem {
        val iconUri = Uri.parse("content://com.ioniqvitals.tiles/tile").buildUpon()
            .appendQueryParameter("lines", lines.joinToString("|"))
            .appendQueryParameter("v", TILE_VERSION.toString())
            .build()
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(" ")
            .setIconUri(iconUri)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    /**
     * One grid tile. The icon + CENTER-justified value/label are baked into a single image served
     * by [TileImageProvider] (the host left-aligns its own item text and offers no alignment
     * control). The host title/subtitle are blanked (" ") so only our centered text shows.
     *
     * PLAYABLE: browsable tiles stopped the light icons from refreshing on state change (the host
     * caches browsable nodes), so the tiles are playable. The downside is the host treats a tap as
     * a play request; taps are swallowed by the MediaSession's inert callback, but the host may
     * still briefly react.
     *
     * The `v` param cache-busts the head unit's image cache: gearhead caches tile images by URL
     * across app reinstalls, so the URL must change when the rendering changes (bump [TILE_VERSION]).
     */
    private fun tile(id: String, title: String, subtitle: String, resId: Int): MediaBrowserCompat.MediaItem {
        val iconUri = Uri.parse("content://com.ioniqvitals.tiles/tile").buildUpon()
            .appendQueryParameter("res", resId.toString())
            .appendQueryParameter("title", title)
            .appendQueryParameter("sub", subtitle)
            .appendQueryParameter("v", TILE_VERSION.toString())
            .build()
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(" ")
            .setIconUri(iconUri)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    /** Signature of the displayed values; a change triggers a single grid refresh. */
    private fun renderKey(d: ObdSnapshot): String {
        fun f1(v: Float?) = v?.let { "%.1f".format(it) } ?: "-"
        fun whole(v: Float?) = v?.toInt()?.toString() ?: "-"
        return "${d.connectionState}|${d.headlightsOn}|${d.brakeLightsOn}|" +
            "${f1(d.socDisplayPercent)}|${f1(d.sohPercent)}|" +
            "${whole(d.coolantTempCelsius)}|${whole(d.auxSocPercent)}"
    }

    companion object {
        private const val TAG = "IoniqVitalsMediaBrowser"
        private const val ROOT_ID = "root"

        // Placeholder shown for a readout that hasn't arrived yet, so its line still renders.
        private const val PENDING = "--"

        // Bump whenever TileImageProvider/tileBitmap rendering changes — the head unit caches tile
        // images by URL, so a new value forces it to re-fetch instead of showing a stale tile.
        private const val TILE_VERSION = 9

        // Android Auto media content-style hints (raw keys, to avoid a media-utils dependency).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        // 1 = list, 2 = grid (large tiles, ~3-4/screen), 4 = category grid (smaller, denser tiles
        // so all six fit without scrolling). The head unit controls cell size / items-per-screen;
        // the icon bitmap size does NOT — this content-style hint is the only app-side lever.
        private const val CONTENT_STYLE_GRID = 2
        private const val CONTENT_STYLE_CATEGORY_GRID = 4
    }
}
