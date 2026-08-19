package com.ioniqvitals.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ioniqvitals.ui.CarIconRenderer
import java.io.File
import java.io.FileOutputStream

/**
 * Serves the media-grid tile images (icon + center-justified value/label) as PNGs over
 * `content://com.ioniqvitals.tiles/tile?res=<id>&title=<value>&sub=<label>`.
 *
 * [IoniqVitalsMediaBrowserService] points each grid item's iconUri here so the Android Auto host
 * loads our centered-text tile directly (instead of a plain drawable + host-aligned text).
 * The query params make each URI unique per displayed value, so the host re-fetches when a
 * value changes and caches otherwise.
 */
class TileImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val renderer = CarIconRenderer(ctx)

        // Stats tile: `lines` param ("|"-separated) → left-justified multi-line text, no icon.
        val lines = uri.getQueryParameter("lines")
        val bitmap: Bitmap
        val safe: String
        if (lines != null) {
            bitmap = renderer.statsBitmap(lines.split("|").filter { it.isNotEmpty() })
            safe = "stats_$lines".replace(Regex("[^A-Za-z0-9._-]"), "_")
        } else {
            val resId = uri.getQueryParameter("res")?.toIntOrNull() ?: return null
            val value = uri.getQueryParameter("title")
            val label = uri.getQueryParameter("sub")
            bitmap = renderer.tileBitmap(resId, value, label)
            safe = "${resId}_${value}_${label}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        }

        val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }
        val file = File(dir, "$safe.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    // Unused — this provider only serves images via openFile.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
