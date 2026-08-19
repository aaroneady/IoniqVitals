package com.ioniqvitals.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.appcompat.content.res.AppCompatResources
import com.ioniqvitals.R
import kotlin.math.min

/**
 * Draws battery SOC graphics on the phone side and converts them to [CarIcon]
 * instances for Android Auto PaneTemplate rows.
 */
class CarIconRenderer(private val context: Context) {

    fun createBatteryRingIcon(percent: Float): CarIcon {
        val clamped = percent.coerceIn(0f, 100f)
        val bitmap = drawProgressRing(clamped)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createBatteryPercentIcon(percent: Float): CarIcon {
        val clamped = percent.coerceIn(0f, 100f)
        val bitmap = drawNumericBadge(clamped)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createTemperatureIcon(tempCelsius: Float): CarIcon {
        val bitmap = drawTemperatureBadge(tempCelsius)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createSohRingIcon(percent: Float): CarIcon {
        val clamped = percent.coerceIn(0f, 100f)
        val bitmap = drawProgressRing(clamped, label = "SOH")
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createOnOffIcon(isOn: Boolean, label: String): CarIcon {
        val bitmap = drawOnOffBadge(isOn, label)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createIconWithOverlay(resId: Int, value: Float, isTemp: Boolean = false): CarIcon {
        val bitmap = drawIconWithOverlay(resId, value, isTemp)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun createScaledIcon(resId: Int, size: Int = 128): CarIcon {
        val drawable = AppCompatResources.getDrawable(context, resId) ?: return CarIcon.APP_ICON
        val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
        val width: Int
        val height: Int
        if (ratio > 1) {
            width = size
            height = (size / ratio).toInt()
        } else {
            height = size
            width = (size * ratio).toInt()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    /**
     * A complete media-grid tile: the icon on top with [value] and [label] CENTER-justified
     * beneath it, baked into one transparent bitmap. Used so the tile text is centered — the
     * Android Auto media-browse host left-aligns its own item title/subtitle and gives no
     * alignment control, so we render the text ourselves and blank the host text.
     */
    fun tileBitmap(resId: Int, value: String?, label: String?, size: Int = 320): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val hasText = !value.isNullOrEmpty() || !label.isNullOrEmpty()

        // Icon fills the tile. Textless tiles (lights) use the entire square; tiles with a
        // value/label reserve only a slim band at the very bottom for the centered text.
        AppCompatResources.getDrawable(context, resId)?.let { drawable ->
            val top = 0f
            val bottom = if (hasText) size * 0.78f else size.toFloat()
            val areaW = size.toFloat()
            val areaH = bottom - top
            val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
            var w = areaW
            var h = areaW / ratio
            if (h > areaH) { h = areaH; w = areaH * ratio }
            val left = (size - w) / 2f
            val iconTop = top + (areaH - h) / 2f
            drawable.setBounds(left.toInt(), iconTop.toInt(), (left + w).toInt(), (iconTop + h).toInt())
            drawable.draw(canvas)
        }

        if (!value.isNullOrEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.14f
                isFakeBoldText = true
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText(value, cx, size * 0.90f, paint)
        }
        if (!label.isNullOrEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#C8D2E0")
                textAlign = Paint.Align.CENTER
                textSize = size * 0.09f
                setShadowLayer(5f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText(label, cx, size * 0.99f, paint)
        }
        return bitmap
    }

    /**
     * A text-only tile: each string in [lines] drawn LEFT-justified on its own row, the block
     * vertically centered. Used for the consolidated stats tile (12V / Temp / SOH / SOC).
     */
    fun statsBitmap(lines: List<String>, size: Int = 320): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (lines.isEmpty()) return bitmap

        val left = size * 0.07f
        val available = size - left * 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            textSize = size * 0.13f
            isFakeBoldText = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }
        // Shrink the text to fit the tile width. Measure against a fixed worst-case line rather
        // than the current content so the font size stays constant as values blank to "--" and
        // back (otherwise the widest line changes and every line rescales). maxOf keeps overflow
        // safe if real content ever exceeds the reference.
        val reference = "Temp: -00°C / -00°F"
        val widest = maxOf(paint.measureText(reference), lines.maxOf { paint.measureText(it) })
        if (widest > available) paint.textSize *= available / widest

        val lineHeight = paint.textSize * 1.5f
        val blockHeight = lineHeight * lines.size
        // First baseline so the block of lines is vertically centered.
        var y = (size - blockHeight) / 2f + paint.textSize
        for (line in lines) {
            canvas.drawText(line, left, y, paint)
            y += lineHeight
        }
        return bitmap
    }

    fun createBitmapWithOverlay(resId: Int, value: Float, isTemp: Boolean = false): Bitmap {
        return drawIconWithOverlay(resId, value, isTemp)
    }

    fun createDashboardBitmap(
        soc: Float?,
        soh: Float?,
        temp: Float?,
        aux: Float?,
        frontOn: Boolean?,
        rearOn: Boolean?,
    ): Bitmap {
        val bitmaps = mutableListOf<Bitmap>()
        
        // Match the order of VehicleStatusScreen: Front, Rear, Aux, Temp, SOH, SOC.
        // Lights are graphical only (no label); the numeric tiles carry a label drawn
        // beneath the icon. SOC/SOH show one decimal place.
        val frontRes = if (frontOn == true) R.drawable.ioniq_5_front_lights_on_comic_book_style else R.drawable.ioniq_5_front_lights_off_comic_book_style
        bitmaps.add(drawDashboardTile(frontRes, null))

        val rearRes = if (rearOn == true) R.drawable.ioniq_5_rear_lights_on_comic_book_style else R.drawable.ioniq_5_rear_lights_off_comic_book_style
        bitmaps.add(drawDashboardTile(rearRes, null, redBorder = rearOn == true))

        aux?.let { bitmaps.add(drawDashboardTile(R.drawable.ic_12v_comic_book_style, "${it.toInt()}%")) }
        temp?.let { bitmaps.add(drawDashboardTile(R.drawable.ic_coolant_comic_book_style, "${it.toInt()}°")) }
        soh?.let { bitmaps.add(drawDashboardTile(R.drawable.ic_soh_comic_book_style, "%.1f%%".format(it))) }
        soc?.let { bitmaps.add(drawDashboardTile(R.drawable.ic_soc_comic_book_style, "%.1f%%".format(it))) }

        if (bitmaps.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val canvasSize = 512
        val bitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Dynamically shift the Palette API anchor color based on the car's state
        val backgroundColor = if (rearOn == true) {
            // A deep, saturated dark red. It forces Android Auto to tint the UI red,
            // but stays dark enough to keep your white text highly legible.
            Color.parseColor("#3A0A0A")
        } else {
            // The default deep, saturated dark blue.
            Color.parseColor("#081A3A")
        }

        // Paint the entire background to force the Palette API's hand
        canvas.drawColor(backgroundColor)
        val iconDisplaySize = 110
        // Smaller horizontal spacing pulls the left/right columns in toward the center
        // column, increasing the margin from the card edges.
        val horizontalSpacing = 6
        val verticalSpacing = 6
        val cols = 3
        val rows = 2
        
        val gridWidth = (iconDisplaySize * cols) + (horizontalSpacing * (cols - 1))
        val gridHeight = (iconDisplaySize * rows) + (verticalSpacing * (rows - 1))
        
        val startX = (canvasSize - gridWidth) / 2f
        // Move icons UP by 60px to escape the bottom scrim area
        val startY = ((canvasSize - gridHeight) / 2f) - 30f
        
        bitmaps.forEachIndexed { index, sourceBitmap ->
            val col = index % cols
            val row = index / cols
            val x = startX + col * (iconDisplaySize + horizontalSpacing)
            val y = startY + row * (iconDisplaySize + verticalSpacing)
            
            val destRect = RectF(x, y, x + iconDisplaySize, y + iconDisplaySize)
            canvas.drawBitmap(sourceBitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }

        return bitmap
    }

    private fun drawScaledBitmap(resId: Int): Bitmap {
        val size = ICON_SIZE_PX
        val drawable = AppCompatResources.getDrawable(context, resId) ?: return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
        
        val width: Int
        val height: Int
        if (ratio > 1) {
            width = size
            height = (size / ratio).toInt()
        } else {
            height = size
            width = (size * ratio).toInt()
        }
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val left = (size - width) / 2
        val top = (size - height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Renders a single dashboard tile: a dark rounded card, the icon in the upper area, and
     * (optionally) a label centered in a band below the icon. The opaque card keeps the white
     * icon/text crisp under the head unit's gradient scrim, which would otherwise wash out
     * graphics baked into the album art. Pass [label] = null for graphic-only tiles (lights).
     * [redBorder] outlines the card in red (used for the brake tile while the lights are on).
     */
    private fun drawDashboardTile(resId: Int, label: String?, redBorder: Boolean = false): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val inset = size * 0.05f
        val radius = size * 0.18f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Near-opaque black so contrast survives the head unit scrim regardless of tint.
            color = Color.argb(215, 0, 0, 0)
        }
        canvas.drawRoundRect(RectF(inset, inset, size - inset, size - inset), radius, radius, bgPaint)

        if (redBorder) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.05f
                color = Color.parseColor("#FF3B30")
            }
            // Inset by half the stroke width so the (path-centered) stroke stays inside the card.
            val half = borderPaint.strokeWidth / 2f
            canvas.drawRoundRect(
                RectF(inset + half, inset + half, size - inset - half, size - inset - half),
                radius, radius, borderPaint,
            )
        }

        val hasLabel = !label.isNullOrEmpty()
        // Reserve the bottom third of the card for the label when present.
        val iconTop = inset
        val iconBottom = if (hasLabel) size * 0.64f else size - inset

        // Draw the icon centered within the icon area, preserving aspect ratio.
        AppCompatResources.getDrawable(context, resId)?.let { drawable ->
            val areaW = size - 2 * inset
            val areaH = iconBottom - iconTop
            val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
            var w = areaW
            var h = areaW / ratio
            if (h > areaH) {
                h = areaH
                w = areaH * ratio
            }
            val left = (size - w) / 2f
            val top = iconTop + (areaH - h) / 2f
            drawable.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
            drawable.draw(canvas)
        }

        if (hasLabel) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.20f
                isFakeBoldText = true
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            // Vertically center the label within the reserved band [iconBottom, size - inset].
            val bandCenterY = (iconBottom + (size - inset)) / 2f
            val fm = textPaint.fontMetrics
            val baseline = bandCenterY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label!!, size / 2f, baseline, textPaint)
        }

        return bitmap
    }

    private fun drawIconWithOverlay(resId: Int, value: Float, isTemp: Boolean): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Load and draw the base icon
        val drawable = AppCompatResources.getDrawable(context, resId)
        drawable?.let {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }

        // Draw the text overlay
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.28f
            isFakeBoldText = true
            // Add a small shadow/outline for readability
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        val text = if (isTemp) "${value.toInt()}°" else "${value.toInt()}%"
        canvas.drawText(text, size / 2f, size / 2f + textPaint.textSize / 3f, textPaint)

        return bitmap
    }

    private fun drawProgressRing(percent: Float, label: String? = null): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stroke = size * 0.08f
        val bounds = RectF(stroke, stroke, size - stroke, size - stroke)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = stroke
            color = Color.parseColor("#334155")
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = stroke
            color = colorForPercent(percent)
            strokeCap = Paint.Cap.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.22f
            isFakeBoldText = true
        }

        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        canvas.drawArc(bounds, -90f, 360f * (percent / 100f), false, progressPaint)
        canvas.drawText("${percent.toInt()}%", size / 2f, size / 2f + textPaint.textSize / 3f, textPaint)
        label?.let {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textAlign = Paint.Align.CENTER
                textSize = size * 0.11f
            }
            canvas.drawText(it, size / 2f, size * 0.82f, labelPaint)
        }

        return bitmap
    }

    private fun drawNumericBadge(percent: Float): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A5568") // Much brighter to counteract the scrim
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorForPercent(percent)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.28f
            isFakeBoldText = true
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            textSize = size * 0.12f
        }

        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.12f, size * 0.12f, backgroundPaint)
        canvas.drawRect(0f, 0f, size.toFloat(), size * 0.08f, accentPaint)
        canvas.drawText("${percent.toInt()}%", size / 2f, size * 0.52f, textPaint)
        canvas.drawText("SOC", size / 2f, size * 0.78f, labelPaint)

        return bitmap
    }

    private fun drawTemperatureBadge(tempCelsius: Float): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A5568") // Much brighter to counteract the scrim
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorForTemperature(tempCelsius)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.24f
            isFakeBoldText = true
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            textSize = size * 0.12f
        }

        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.12f, size * 0.12f, backgroundPaint)
        canvas.drawRect(0f, 0f, size.toFloat(), size * 0.08f, accentPaint)
        canvas.drawText("${tempCelsius.toInt()}°", size / 2f, size * 0.52f, textPaint)
        canvas.drawText("TEMP", size / 2f, size * 0.78f, labelPaint)

        return bitmap
    }

    private fun drawOnOffBadge(isOn: Boolean, label: String): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A5568") // Much brighter to counteract the scrim
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isOn) Color.parseColor("#22C55E") else Color.parseColor("#64748B")
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.22f
            isFakeBoldText = true
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            textSize = size * 0.11f
        }

        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.12f, size * 0.12f, backgroundPaint)
        canvas.drawRect(0f, 0f, size.toFloat(), size * 0.08f, accentPaint)
        canvas.drawText(if (isOn) "ON" else "OFF", size / 2f, size * 0.52f, textPaint)
        canvas.drawText(label, size / 2f, size * 0.78f, labelPaint)

        return bitmap
    }

    private fun colorForPercent(percent: Float): Int {
        val ratio = (percent / 100f).coerceIn(0f, 1f)
        val red = (255 * (1f - ratio)).toInt()
        val green = (200 * ratio + 55 * (1f - ratio)).toInt()
        return Color.rgb(red, green, 80)
    }

    private fun colorForTemperature(tempCelsius: Float): Int {
        val normalized = ((tempCelsius - 10f) / 40f).coerceIn(0f, 1f)
        val blue = (255 * (1f - normalized)).toInt()
        val red = (255 * normalized).toInt()
        return Color.rgb(red, min(180, 120 + (normalized * 135).toInt()), blue)
    }

    companion object {
        private const val ICON_SIZE_PX = 128
    }
}
