package com.esp32.navlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class CompassTapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var headingDeg = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 2f
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 2.6f
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        strokeWidth = 1.6f
    }

    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val degPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DC2626")
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181C24")
        style = Paint.Style.FILL
    }

    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#991B1B")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val pointerPath = Path()
    private val badgeRect = RectF()

    fun setHeadingDegrees(deg: Float) {
        val norm = ((deg % 360f) + 360f) % 360f
        if (kotlin.math.abs(norm - headingDeg) < 0.4f) return
        headingDeg = norm
        invalidate()
    }

    private fun degreeToCardinal(deg: Int): String? {
        return when (((deg % 360) + 360) % 360) {
            0 -> "N"
            45 -> "NE"
            90 -> "E"
            135 -> "SE"
            180 -> "S"
            225 -> "SW"
            270 -> "W"
            315 -> "NW"
            else -> null
        }
    }

    private fun exactCardinal(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return dirs[idx]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        cardinalPaint.textSize = 12f * density
        degPaint.textSize = 9f * density
        badgeTextPaint.textSize = 10f * density

        val centerX = w / 2f
        val baselineY = h - 18f * density
        val pxPerDeg = w / 110f // ~110 degree aviation FOV window

        // Horizontal scale baseline
        canvas.drawLine(8f * density, baselineY, w - 8f * density, baselineY, linePaint)

        val startDeg = (headingDeg - 60f).toInt()
        val endDeg = (headingDeg + 60f).toInt()
        val firstTick = startDeg - (startDeg % 5)

        var d = firstTick
        while (d <= endDeg) {
            val delta = d - headingDeg
            val x = centerX + delta * pxPerDeg
            if (x >= 10f * density && x <= w - 10f * density) {
                val norm = ((d % 360) + 360) % 360
                val isCardinal = (norm % 45 == 0)
                val isMajor = (norm % 15 == 0)
                val tickH = when {
                    isCardinal -> 10f * density
                    isMajor -> 7f * density
                    else -> 4f * density
                }
                val paint = if (isCardinal || isMajor) majorTickPaint else minorTickPaint
                canvas.drawLine(x, baselineY - tickH, x, baselineY, paint)

                val card = degreeToCardinal(norm)
                if (card != null) {
                    canvas.drawText(card, x, baselineY - 14f * density, cardinalPaint)
                } else if (norm % 30 == 0) {
                    canvas.drawText("${norm}°", x, baselineY - 14f * density, degPaint)
                }
            }
            d += 5
        }

        // Fixed Center Crimson Lubber Triangle
        val triSize = 5.5f * density
        pointerPath.reset()
        pointerPath.moveTo(centerX, baselineY + 1f * density)
        pointerPath.lineTo(centerX - triSize, baselineY + 1f * density + triSize * 1.25f)
        pointerPath.lineTo(centerX + triSize, baselineY + 1f * density + triSize * 1.25f)
        pointerPath.close()
        canvas.drawPath(pointerPath, pointerPaint)

        // Subtle center heading pill at bottom
        val label = "${exactCardinal(headingDeg)}${headingDeg.roundToInt()}°"
        val pillW = 58f * density
        val pillH = 15f * density
        badgeRect.set(
            centerX - pillW / 2f,
            h - pillH - 1f * density,
            centerX + pillW / 2f,
            h - 1f * density
        )
        canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeStrokePaint)
        val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(label, centerX, textY, badgeTextPaint)
    }
}

class SpeedArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var speedKmh = 0
    private val maxScaleSpeed = 160f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E232E")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 2f
    }

    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B91C1C")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val arcRect = RectF()

    fun setSpeed(kmh: Int) {
        val clamped = kmh.coerceIn(0, 299)
        if (clamped == speedKmh) return
        speedKmh = clamped
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            activePaint.shader = LinearGradient(
                0f, h.toFloat(), w.toFloat(), 0f,
                intArrayOf(
                    Color.parseColor("#CBD5E1"),
                    Color.parseColor("#94A3B8"),
                    Color.parseColor("#B91C1C")
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val strokeW = 10f * density
        trackPaint.strokeWidth = strokeW
        activePaint.strokeWidth = strokeW

        val diameter = min(w, h) - strokeW * 2.6f
        val radius = diameter / 2f
        val cx = w / 2f
        val cy = h / 2f + 6f * density

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Subtle outer radial racing ticks along the 240 deg arc
        val tickOuter = radius + strokeW * 0.95f
        val tickInner = radius + strokeW * 0.62f
        for (i in 0..16) {
            val angleDeg = 150.0 + (240.0 * i / 16.0)
            val rad = Math.toRadians(angleDeg)
            val sx = cx + (cos(rad) * tickInner).toFloat()
            val sy = cy + (sin(rad) * tickInner).toFloat()
            val ex = cx + (cos(rad) * tickOuter).toFloat()
            val ey = cy + (sin(rad) * tickOuter).toFloat()
            canvas.drawLine(sx, sy, ex, ey, tickPaint)
        }

        // Background track arc (240 degree sweep)
        canvas.drawArc(arcRect, 150f, 240f, false, trackPaint)

        // Active speed sweep arc
        val fraction = (speedKmh.toFloat() / maxScaleSpeed).coerceIn(0f, 1f)
        val sweep = if (speedKmh > 0) (fraction * 240f).coerceAtLeast(6f) else 0f
        if (sweep > 0f) {
            canvas.drawArc(arcRect, 150f, sweep, false, activePaint)
        }

        // Big crisp Speed readout inside circle
        speedTextPaint.textSize = 48f * density
        unitTextPaint.textSize = 11f * density
        val speedY = cy + 8f * density
        canvas.drawText(speedKmh.toString(), cx, speedY, speedTextPaint)
        canvas.drawText("KM/H", cx, speedY + 20f * density, unitTextPaint)
    }
}
