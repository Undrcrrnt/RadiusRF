package app.radiusrf.features.wifi.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.max

/** Draws a saved floor walk to a PNG. The camera picture is not included. */
object FloorWalkImage {
    private const val WIDTH = 1080
    private const val PAD = 56
    private const val BG = 0xFF0B0D0C.toInt()
    private const val MAP = 0xFF182019.toInt()
    private const val INK = 0xFFE6ECE8.toInt()
    private const val MUTED = 0xFFA3B0A8.toInt()
    private const val ACCENT = 0xFF52E08A.toInt()
    private const val WEAK = 0xFFFF6B61.toInt()
    private const val MID = 0xFFF2B53C.toInt()
    private const val STRONG = 0xFF52E08A.toInt()

    fun write(record: FloorWalkRecord, whenText: String, file: File) {
        val sheet = FloorWalkMath.sheet(record, whenText)
        val content = WIDTH - PAD * 2
        val kicker = textPaint(28f, ACCENT, bold = true)
        val title = textPaint(52f, INK, bold = true)
        val body = textPaint(30f, MUTED, bold = false)
        val note = textPaint(28f, MUTED, bold = false)
        val section = textPaint(28f, ACCENT, bold = true)
        val kickerLayout = layout("RadiusRF floor walk", kicker, content)
        val titleLayout = layout(sheet.title, title, content)
        val detailLayout = layout(sheet.detail, body, content)
        val noteLayout = layout(sheet.note, note, content)
        val mapSize = content.toFloat()
        val legendHeight = 88
        val roamBlock = if (sheet.roams.isEmpty()) 0 else 56 + sheet.roams.size * 44
        val height = PAD + kickerLayout.height + 16 + titleLayout.height + 12 + detailLayout.height +
            32 + mapSize.toInt() + 28 + legendHeight + 20 + noteLayout.height + 24 + roamBlock + PAD
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)
        var y = PAD.toFloat()
        y = drawLayout(canvas, kickerLayout, PAD.toFloat(), y) + 16f
        y = drawLayout(canvas, titleLayout, PAD.toFloat(), y) + 12f
        y = drawLayout(canvas, detailLayout, PAD.toFloat(), y) + 32f
        drawMap(canvas, record.samples, sheet.scaleLabel, PAD.toFloat(), y, mapSize)
        y += mapSize + 28f
        drawLegend(canvas, PAD.toFloat(), y, content.toFloat())
        y += legendHeight + 20f
        y = drawLayout(canvas, noteLayout, PAD.toFloat(), y) + 24f
        if (sheet.roams.isNotEmpty()) {
            val heading = layout("Access point changes", section, content)
            y = drawLayout(canvas, heading, PAD.toFloat(), y) + 12f
            val line = textPaint(30f, INK, bold = false)
            sheet.roams.forEach { roam ->
                val row = layout(roam, line, content)
                y = drawLayout(canvas, row, PAD.toFloat(), y) + 8f
            }
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun drawMap(
        canvas: Canvas,
        samples: List<FloorSample>,
        scaleLabel: String,
        left: Float,
        top: Float,
        size: Float
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MAP }
        canvas.drawRoundRect(left, top, left + size, top + size, 28f, 28f, fill)
        if (samples.isEmpty()) return
        val span = FloorWalkMath.spanMetres(samples)
        val minX = samples.minOf { it.x }
        val maxX = samples.maxOf { it.x }
        val minZ = samples.minOf { it.z }
        val maxZ = samples.maxOf { it.z }
        val cx = (minX + maxX) / 2.0
        val cz = (minZ + maxZ) / 2.0
        val inset = 80f
        val scale = (size - inset * 2f) / span.toFloat()
        fun at(sample: FloorSample): Pair<Float, Float> {
            val x = left + size / 2f + ((sample.x - cx) * scale).toFloat()
            val y = top + size / 2f - ((sample.z - cz) * scale).toFloat()
            return x to y
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (index in 1 until samples.size) {
            stroke.color = heat(samples[index].rssi)
            val start = at(samples[index - 1])
            val end = at(samples[index])
            canvas.drawLine(start.first, start.second, end.first, end.second, stroke)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = android.graphics.Color.WHITE
        }
        samples.forEach { sample ->
            val point = at(sample)
            if (sample.roam) canvas.drawCircle(point.first, point.second, 18f, ring)
            dot.color = heat(sample.rssi)
            canvas.drawCircle(point.first, point.second, 9f, dot)
        }
        val label = textPaint(26f, INK, bold = false)
        samples.filter { it.roam && !it.bssid.isNullOrBlank() }.take(12).forEach { sample ->
            val point = at(sample)
            val text = sample.bssid.orEmpty()
            val textWidth = label.measureText(text)
            val x = if (point.first + 24f + textWidth < left + size - 20f) {
                point.first + 24f
            } else {
                point.first - 24f - textWidth
            }
            canvas.drawText(text, x, point.second - 22f, label)
        }
        val barPx = (FloorWalkMath.scaleBarMetres(span) * scale).toFloat()
            .coerceAtMost(size - 72f)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        val barX = left + 36f
        val barY = top + size - 56f
        canvas.drawLine(barX, barY, barX + barPx, barY, bar)
        canvas.drawText(scaleLabel, barX, barY - 14f, textPaint(26f, MUTED, bold = false))
    }

    private fun drawLegend(canvas: Canvas, left: Float, top: Float, width: Float) {
        val labels = listOf("-100 dBm", "mid", "-30 dBm")
        val colors = listOf(WEAK, MID, STRONG)
        val paint = textPaint(26f, MUTED, bold = false)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val slot = width / labels.size
        labels.forEachIndexed { index, label ->
            val x = left + slot * index
            dot.color = colors[index]
            canvas.drawCircle(x + 12f, top + 16f, 10f, dot)
            canvas.drawText(label, x + 30f, top + 26f, paint)
        }
    }

    private fun heat(rssi: Int): Int {
        val t = FloorWalkMath.strength(rssi)
        return if (t < 0.5f) mix(WEAK, MID, t * 2f) else mix(MID, STRONG, (t - 0.5f) * 2f)
    }

    private fun mix(start: Int, end: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val from = (start shr shift) and 0xFF
            val to = (end shr shift) and 0xFF
            return (from + (to - from) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun drawLayout(canvas: Canvas, block: StaticLayout, x: Float, y: Float): Float {
        canvas.save()
        canvas.translate(x, y)
        block.draw(canvas)
        canvas.restore()
        return y + block.height
    }
}
