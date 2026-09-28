package com.stormreachhaven.stormreachgame.ui

import android.content.res.Resources
import android.graphics.*
import kotlin.math.max
import kotlin.math.min

enum class Icon { HOME, FORGE, GEM, GEAR, BOLT, CHEVRON_LEFT, PAUSE, CHECK, LOCK, SPARK, REPEAT }

enum class BtnKind { GOLD, BLUE, GREEN, DARK }

object Palette {
    const val INK = 0xFF030C1C.toInt()
    const val INK_SOFT = 0xFF0B2140.toInt()
    const val GOLD = 0xFFE3AC3B.toInt()
    const val GOLD_LIGHT = 0xFFFFE6A6.toInt()
    const val GOLD_DEEP = 0xFF8A5B12.toInt()
    const val CYAN = 0xFF5FDBFF.toInt()
    const val BLUE = 0xFF2A6FC4.toInt()
    const val GREEN = 0xFF2E9B4E.toInt()
    const val RED = 0xFFD8453C.toInt()
    const val VIOLET = 0xFFA35CFF.toInt()
    const val TEXT = 0xFFF2F7FF.toInt()
    const val TEXT_DIM = 0xFF9FB6D4.toInt()
}

/**
 * Immediate-mode drawing helpers shared by every screen. Only paints pixels:
 * touch handling stays in the view so hit areas follow the same layout numbers.
 */
class UiKit(private val resources: Resources) {

    var w = 0f
    var h = 0f
    var time = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val srcRect = Rect()
    private val bold = Typeface.create("sans-serif-black", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Float) = value * resources.displayMetrics.density

    fun ts(value: Float) = value * resources.displayMetrics.density

    fun r(l: Float, t: Float, right: Float, b: Float) = RectF(w * l, h * t, w * right, h * b)

    // ---------------------------------------------------------------- backdrop

    fun backdrop(c: Canvas, bitmap: Bitmap?, dim: Int) {
        if (bitmap == null) {
            c.drawColor(Palette.INK)
        } else {
            val scale = max(w / bitmap.width, h / bitmap.height)
            val srcW = w / scale
            val srcH = h / scale
            val left = (bitmap.width - srcW) / 2f
            val top = (bitmap.height - srcH) / 2f
            srcRect.set(left.toInt(), top.toInt(), (left + srcW).toInt(), (top + srcH).toInt())
            fill.shader = null
            fill.alpha = 255
            c.drawBitmap(bitmap, srcRect, RectF(0f, 0f, w, h), fill)
        }
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(min(255, (dim * 1.3f).toInt()), 3, 11, 26),
                Color.argb(min(255, (dim * 0.5f).toInt()), 5, 16, 36),
                Color.argb(min(255, (dim * 1.45f).toInt()), 2, 7, 18)
            ),
            floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        vignette(c)
    }

    fun vignette(c: Canvas) {
        fill.shader = RadialGradient(
            w / 2f, h * .44f, max(w, h) * .74f,
            intArrayOf(Color.TRANSPARENT, Color.argb(20, 0, 2, 8), Color.argb(165, 0, 2, 8)),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    fun glow(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        if (radius <= 0f) return
        fill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(alpha / 3, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius, fill)
        fill.shader = null
    }

    fun rays(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, count: Int, spin: Float) {
        fill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(.15f, 1f), Shader.TileMode.CLAMP
        )
        c.save()
        c.rotate(spin, cx, cy)
        for (i in 0 until count) {
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx - radius * .09f, cy - radius)
            path.lineTo(cx + radius * .09f, cy - radius)
            path.close()
            c.drawPath(path, fill)
            c.rotate(360f / count, cx, cy)
        }
        c.restore()
        fill.shader = null
    }

    // ------------------------------------------------------------------ panels

    fun softShadow(c: Canvas, box: RectF, corner: Float, layers: Int = 4, alpha: Int = 24) {
        fill.shader = null
        fill.color = Color.argb(alpha, 0, 0, 0)
        for (i in layers downTo 1) {
            val g = dp(i * 2.1f)
            c.drawRoundRect(
                RectF(box.left - g, box.top - g + dp(2f), box.right + g, box.bottom + g + dp(2f)),
                corner + g, corner + g, fill
            )
        }
    }

    fun panel(c: Canvas, box: RectF, corner: Float = dp(16f), ornaments: Boolean = true, shadow: Boolean = true) {
        if (shadow) softShadow(c, box, corner)
        fill.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            intArrayOf(0xFA173D6C.toInt(), 0xFA071B3C.toInt(), 0xFD030B1C.toInt()),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(box, corner, corner, fill)
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.top + box.height() * .5f,
            Color.argb(44, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(
            RectF(box.left + dp(2f), box.top + dp(2f), box.right - dp(2f), box.top + box.height() * .5f),
            corner, corner, fill
        )
        fill.shader = null
        line.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            intArrayOf(Palette.GOLD_LIGHT, Palette.GOLD, 0xFF5E8FCB.toInt(), Palette.GOLD),
            null, Shader.TileMode.CLAMP
        )
        line.strokeWidth = dp(1.7f)
        c.drawRoundRect(box, corner, corner, line)
        line.shader = null
        line.color = Color.argb(95, 0, 0, 0)
        line.strokeWidth = dp(1f)
        c.drawRoundRect(
            RectF(box.left + dp(3.5f), box.top + dp(3.5f), box.right - dp(3.5f), box.bottom - dp(3.5f)),
            corner - dp(2f), corner - dp(2f), line
        )
        if (ornaments) cornerAccents(c, box)
    }

    fun slate(c: Canvas, box: RectF, corner: Float = dp(12f), highlight: Boolean = false, dim: Boolean = false) {
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            if (highlight) intArrayOf(0xFF23548F.toInt(), 0xFF08203F.toInt())
            else if (dim) intArrayOf(0xF20A1526.toInt(), 0xF2050B16.toInt())
            else intArrayOf(0xFA10305C.toInt(), 0xFA04101F.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(box, corner, corner, fill)
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.top + box.height() * .5f,
            Color.argb(if (dim) 12 else 34, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(
            RectF(box.left + dp(1.5f), box.top + dp(1.5f), box.right - dp(1.5f), box.top + box.height() * .5f),
            corner, corner, fill
        )
        fill.shader = null
        line.color = when {
            highlight -> Palette.GOLD
            dim -> Color.argb(70, 130, 155, 190)
            else -> Color.argb(120, 190, 155, 75)
        }
        line.strokeWidth = dp(if (highlight) 1.7f else 1.1f)
        c.drawRoundRect(box, corner, corner, line)
    }

    private fun cornerAccents(c: Canvas, box: RectF) {
        line.shader = null
        line.color = Palette.GOLD_LIGHT
        line.strokeWidth = dp(2.2f)
        val s = dp(13f)
        val p = dp(8f)
        c.drawLine(box.left + p, box.top + p + s, box.left + p, box.top + p, line)
        c.drawLine(box.left + p, box.top + p, box.left + p + s, box.top + p, line)
        c.drawLine(box.right - p - s, box.top + p, box.right - p, box.top + p, line)
        c.drawLine(box.right - p, box.top + p, box.right - p, box.top + p + s, line)
        c.drawLine(box.left + p, box.bottom - p - s, box.left + p, box.bottom - p, line)
        c.drawLine(box.left + p, box.bottom - p, box.left + p + s, box.bottom - p, line)
        c.drawLine(box.right - p - s, box.bottom - p, box.right - p, box.bottom - p, line)
        c.drawLine(box.right - p, box.bottom - p, box.right - p, box.bottom - p - s, line)
    }

    fun divider(c: Canvas, cx: Float, cy: Float, halfWidth: Float) {
        line.shader = LinearGradient(
            cx - halfWidth, 0f, cx + halfWidth, 0f,
            intArrayOf(Color.TRANSPARENT, Palette.GOLD, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        line.strokeWidth = dp(1.4f)
        c.drawLine(cx - halfWidth, cy, cx + halfWidth, cy, line)
        line.shader = null
        fill.shader = null
        fill.color = Palette.GOLD_LIGHT
        diamond(c, cx, cy, dp(4f))
        fill.color = Palette.GOLD
        diamond(c, cx - halfWidth * .55f, cy, dp(2.6f))
        diamond(c, cx + halfWidth * .55f, cy, dp(2.6f))
    }

    private fun diamond(c: Canvas, cx: Float, cy: Float, radius: Float) {
        path.reset()
        path.moveTo(cx, cy - radius)
        path.lineTo(cx + radius, cy)
        path.lineTo(cx, cy + radius)
        path.lineTo(cx - radius, cy)
        path.close()
        c.drawPath(path, fill)
    }

    fun topBar(c: Canvas, bottom: Float, title: String) {
        val box = RectF(0f, 0f, w, h * bottom)
        fill.shader = LinearGradient(
            0f, 0f, 0f, box.bottom,
            intArrayOf(0xF802080F.toInt(), 0xF00A1E3C.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRect(box, fill)
        fill.shader = null
        line.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.TRANSPARENT, Palette.GOLD, Palette.GOLD_LIGHT, Palette.GOLD, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        line.strokeWidth = dp(2f)
        c.drawLine(0f, box.bottom, w, box.bottom, line)
        line.shader = null
        val size = ts(17f)
        val cy = box.centerY() + dp(2f)
        text(c, title, w / 2f, cy, size, Palette.TEXT, Paint.Align.CENTER, spacing = .16f, middle = true)
        val half = measure(title, size, spacing = .16f) / 2f
        val accent = w * .08f
        line.shader = null
        line.strokeWidth = dp(1.4f)
        line.color = Palette.GOLD
        fill.shader = null
        fill.color = Palette.GOLD_LIGHT
        listOf(-1f, 1f).forEach { side ->
            val from = w / 2f + side * (half + dp(14f))
            val to = from + side * accent
            line.shader = LinearGradient(
                from, 0f, to, 0f, intArrayOf(Palette.GOLD, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            c.drawLine(from, cy, to, cy, line)
            diamond(c, from, cy, dp(3.2f))
        }
        line.shader = null
    }

    // ----------------------------------------------------------------- buttons

    fun button(
        c: Canvas, box0: RectF, label: String, kind: BtnKind,
        enabled: Boolean = true, pressed: Boolean = false, glyph: Icon? = null, textSize: Float = ts(15f)
    ) {
        val down = pressed && enabled
        val box = if (down) RectF(box0.left, box0.top + dp(3f), box0.right, box0.bottom + dp(1f)) else box0
        val corner = dp(15f)
        if (!down && enabled) softShadow(c, box, corner, 5, 22)
        val base = when {
            !enabled -> 0xFF39485C.toInt()
            kind == BtnKind.GOLD -> 0xFFDCA330.toInt()
            kind == BtnKind.BLUE -> Palette.BLUE
            kind == BtnKind.GREEN -> Palette.GREEN
            else -> 0xFF11294C.toInt()
        }
        fill.shader = null
        fill.color = 0xFF050E1E.toInt()
        c.drawRoundRect(box, corner, corner, fill)
        val inner = RectF(box.left + dp(2.2f), box.top + dp(2.2f), box.right - dp(2.2f), box.bottom - dp(2.2f))
        val innerCorner = corner - dp(2f)
        fill.shader = LinearGradient(
            0f, inner.top, 0f, inner.bottom,
            intArrayOf(lighten(base, .42f), base, darken(base, .38f)),
            floatArrayOf(0f, .54f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(inner, innerCorner, innerCorner, fill)
        fill.shader = LinearGradient(
            0f, inner.top, 0f, inner.top + inner.height() * .55f,
            Color.argb(if (enabled) 112 else 42, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(
            RectF(inner.left + dp(2f), inner.top + dp(1.5f), inner.right - dp(2f), inner.top + inner.height() * .55f),
            innerCorner, innerCorner, fill
        )
        fill.shader = LinearGradient(
            0f, inner.bottom - inner.height() * .4f, 0f, inner.bottom,
            Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(inner, innerCorner, innerCorner, fill)
        fill.shader = null
        line.color = if (enabled) Palette.GOLD_LIGHT else Color.argb(120, 150, 165, 185)
        line.strokeWidth = dp(1.6f)
        c.drawRoundRect(inner, innerCorner, innerCorner, line)

        val color = if (enabled) Palette.TEXT else Color.argb(170, 205, 215, 230)
        tp.typeface = bold
        tp.textSize = textSize
        tp.letterSpacing = .1f
        val labelWidth = tp.measureText(label)
        if (glyph == null) {
            text(c, label, box.centerX(), box.centerY(), textSize, color, Paint.Align.CENTER, spacing = .1f, middle = true)
        } else {
            val gap = dp(9f)
            val iconSize = textSize * 1.35f
            val total = labelWidth + gap + iconSize
            val startX = box.centerX() - total / 2f
            icon(
                c,
                RectF(startX, box.centerY() - iconSize / 2f, startX + iconSize, box.centerY() + iconSize / 2f),
                glyph, if (enabled) Palette.GOLD_LIGHT else Color.LTGRAY
            )
            text(
                c, label, startX + iconSize + gap, box.centerY(), textSize, color,
                Paint.Align.LEFT, spacing = .1f, middle = true
            )
        }
    }

    fun iconButton(c: Canvas, box: RectF, kind: Icon, pressed: Boolean = false) {
        val corner = dp(13f)
        val target = if (pressed) RectF(box.left, box.top + dp(2f), box.right, box.bottom + dp(2f)) else box
        fill.shader = LinearGradient(
            0f, target.top, 0f, target.bottom,
            intArrayOf(0xF01A3E69.toInt(), 0xF0071630.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(target, corner, corner, fill)
        fill.shader = null
        line.color = Palette.GOLD
        line.strokeWidth = dp(1.5f)
        c.drawRoundRect(target, corner, corner, line)
        val pad = target.width() * .28f
        icon(
            c,
            RectF(target.left + pad, target.top + pad, target.right - pad, target.bottom - pad),
            kind, Palette.GOLD_LIGHT
        )
    }

    fun toggle(c: Canvas, box: RectF, on: Boolean) {
        val corner = box.height() / 2f
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            if (on) intArrayOf(lighten(Palette.GREEN, .3f), darken(Palette.GREEN, .25f))
            else intArrayOf(0xFF1B2637.toInt(), 0xFF0C1522.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(box, corner, corner, fill)
        fill.shader = null
        line.color = if (on) Palette.GOLD_LIGHT else Color.argb(120, 130, 150, 175)
        line.strokeWidth = dp(1.4f)
        c.drawRoundRect(box, corner, corner, line)
        val knobR = box.height() * .34f
        val cx = if (on) box.right - corner else box.left + corner
        fill.shader = RadialGradient(
            cx, box.centerY() - knobR * .4f, knobR * 1.7f,
            intArrayOf(Color.WHITE, 0xFFB9C9DD.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, box.centerY(), knobR, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------- progress

    fun progress(
        c: Canvas, box: RectF, value: Float, from: Int, to: Int,
        segments: Int = 0, shine: Boolean = true
    ) {
        val corner = box.height() / 2f
        fill.shader = null
        fill.color = 0xFF060F1F.toInt()
        c.drawRoundRect(box, corner, corner, fill)
        val inner = RectF(box.left + dp(1.5f), box.top + dp(1.5f), box.right - dp(1.5f), box.bottom - dp(1.5f))
        fill.color = 0xFF101B2C.toInt()
        c.drawRoundRect(inner, corner, corner, fill)
        val clamped = value.coerceIn(0f, 1f)
        if (clamped > 0f) {
            val filled = RectF(inner.left, inner.top, inner.left + inner.width() * clamped, inner.bottom)
            fill.shader = LinearGradient(inner.left, 0f, inner.right, 0f, from, to, Shader.TileMode.CLAMP)
            c.drawRoundRect(filled, corner, corner, fill)
            fill.shader = LinearGradient(
                0f, filled.top, 0f, filled.centerY(),
                Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(
                RectF(filled.left, filled.top, filled.right, filled.centerY()),
                corner, corner, fill
            )
            fill.shader = null
            if (shine) glow(c, filled.right, filled.centerY(), box.height() * 1.5f, Palette.GOLD_LIGHT, 150)
        }
        if (segments > 1) {
            line.shader = null
            line.color = Color.argb(150, 3, 10, 22)
            line.strokeWidth = dp(1.4f)
            for (i in 1 until segments) {
                val x = inner.left + inner.width() * i / segments
                c.drawLine(x, inner.top, x, inner.bottom, line)
            }
        }
        line.shader = null
        line.color = Color.argb(200, 226, 172, 59)
        line.strokeWidth = dp(1.3f)
        c.drawRoundRect(box, corner, corner, line)
    }

    fun pips(c: Canvas, cx: Float, cy: Float, count: Int, filledCount: Int, radius: Float, gap: Float, color: Int) {
        val total = count * radius * 2 + (count - 1) * gap
        var x = cx - total / 2f + radius
        for (i in 0 until count) {
            fill.shader = null
            if (i < filledCount) {
                glow(c, x, cy, radius * 2.4f, color, 90)
                fill.color = color
                c.drawCircle(x, cy, radius, fill)
                fill.color = Color.argb(150, 255, 255, 255)
                c.drawCircle(x - radius * .25f, cy - radius * .3f, radius * .35f, fill)
            } else {
                fill.color = Color.argb(110, 12, 26, 48)
                c.drawCircle(x, cy, radius, fill)
                line.shader = null
                line.color = Color.argb(130, 150, 170, 200)
                line.strokeWidth = dp(1.2f)
                c.drawCircle(x, cy, radius, line)
            }
            x += radius * 2 + gap
        }
    }

    // ------------------------------------------------------------------- icons

    fun icon(c: Canvas, box: RectF, kind: Icon, color: Int) {
        val light = lighten(color, .5f)
        fill.shader = LinearGradient(0f, box.top, 0f, box.bottom, light, color, Shader.TileMode.CLAMP)
        line.shader = null
        line.color = Color.argb(160, 3, 10, 24)
        line.strokeWidth = dp(1.2f)
        when (kind) {
            Icon.BOLT -> stroked(c, poly(box, BOLT))
            Icon.GEM -> {
                stroked(c, poly(box, GEM))
                line.color = Color.argb(120, 255, 255, 255)
                c.drawLine(box.left + box.width() * .06f, box.top + box.height() * .38f,
                    box.left + box.width() * .94f, box.top + box.height() * .38f, line)
            }
            Icon.HOME -> {
                stroked(c, poly(box, ROOF))
                c.drawRect(rel(box, .12f, .38f, .88f, .46f), fill)
                c.drawRect(rel(box, .2f, .46f, .3f, .84f), fill)
                c.drawRect(rel(box, .45f, .46f, .55f, .84f), fill)
                c.drawRect(rel(box, .7f, .46f, .8f, .84f), fill)
                c.drawRect(rel(box, .08f, .84f, .92f, .94f), fill)
            }
            Icon.FORGE -> {
                stroked(c, poly(box, ANVIL))
                fill.shader = null
                fill.color = Palette.CYAN
                c.drawPath(poly(RectF(box.left + box.width() * .34f, box.top,
                    box.left + box.width() * .66f, box.top + box.height() * .3f), BOLT), fill)
            }
            Icon.GEAR -> {
                val cx = box.centerX()
                val cy = box.centerY()
                val outer = box.width() * .46f
                for (k in 0 until 8) {
                    c.save()
                    c.rotate(k * 45f, cx, cy)
                    c.drawRoundRect(
                        RectF(cx - box.width() * .08f, cy - outer, cx + box.width() * .08f, cy - box.width() * .16f),
                        dp(2f), dp(2f), fill
                    )
                    c.restore()
                }
                c.drawCircle(cx, cy, box.width() * .3f, fill)
                fill.shader = null
                fill.color = 0xFF071528.toInt()
                c.drawCircle(cx, cy, box.width() * .12f, fill)
            }
            Icon.SPARK -> {
                stroked(c, poly(box, SPARK_V))
                c.drawPath(poly(box, SPARK_H), fill)
            }
            Icon.LOCK -> {
                c.drawRoundRect(rel(box, .18f, .44f, .82f, .94f), dp(3f), dp(3f), fill)
                line.color = color
                line.strokeWidth = box.width() * .13f
                path.reset()
                path.addArc(rel(box, .3f, .1f, .7f, .58f), 180f, 180f)
                c.drawPath(path, line)
            }
            Icon.CHECK -> {
                line.color = color
                line.strokeWidth = box.width() * .17f
                path.reset()
                path.moveTo(box.left + box.width() * .16f, box.top + box.height() * .54f)
                path.lineTo(box.left + box.width() * .41f, box.top + box.height() * .79f)
                path.lineTo(box.left + box.width() * .86f, box.top + box.height() * .22f)
                c.drawPath(path, line)
            }
            Icon.CHEVRON_LEFT -> {
                line.color = color
                line.strokeWidth = box.width() * .16f
                path.reset()
                path.moveTo(box.left + box.width() * .66f, box.top + box.height() * .12f)
                path.lineTo(box.left + box.width() * .32f, box.centerY())
                path.lineTo(box.left + box.width() * .66f, box.top + box.height() * .88f)
                c.drawPath(path, line)
            }
            Icon.PAUSE -> {
                c.drawRoundRect(rel(box, .24f, .14f, .43f, .86f), dp(2.5f), dp(2.5f), fill)
                c.drawRoundRect(rel(box, .57f, .14f, .76f, .86f), dp(2.5f), dp(2.5f), fill)
            }
            Icon.REPEAT -> {
                line.color = color
                line.strokeWidth = box.width() * .13f
                path.reset()
                path.addArc(rel(box, .12f, .12f, .88f, .88f), 40f, 280f)
                c.drawPath(path, line)
                c.drawPath(poly(rel(box, .58f, .0f, .98f, .34f), ARROW), fill)
            }
        }
        fill.shader = null
    }

    private fun stroked(c: Canvas, p: Path) {
        c.drawPath(p, fill)
        c.drawPath(p, line)
    }

    private fun rel(box: RectF, l: Float, t: Float, right: Float, b: Float) = RectF(
        box.left + box.width() * l, box.top + box.height() * t,
        box.left + box.width() * right, box.top + box.height() * b
    )

    private fun poly(box: RectF, pts: FloatArray): Path {
        path.reset()
        var i = 0
        while (i < pts.size) {
            val x = box.left + box.width() * pts[i]
            val y = box.top + box.height() * pts[i + 1]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        path.close()
        return path
    }

    // -------------------------------------------------------------------- text

    fun text(
        c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, heavy: Boolean = true,
        spacing: Float = .04f, shadow: Boolean = true, middle: Boolean = false
    ) {
        tp.typeface = if (heavy) bold else medium
        tp.textSize = size
        tp.letterSpacing = spacing
        tp.textAlign = align
        tp.color = color
        val baseline = if (middle) {
            val fm = tp.fontMetrics
            y - (fm.ascent + fm.descent) / 2f
        } else y
        if (shadow) {
            tp.setShadowLayer(size * .3f, 0f, size * .08f, Color.argb(190, 0, 2, 8))
        }
        c.drawText(value, x, baseline, tp)
        tp.clearShadowLayer()
    }

    fun titleText(c: Canvas, value: String, cx: Float, cy: Float, size: Float) {
        tp.typeface = bold
        tp.textSize = size
        tp.letterSpacing = .12f
        tp.textAlign = Paint.Align.CENTER
        val fm = tp.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        tp.style = Paint.Style.STROKE
        tp.strokeWidth = size * .1f
        tp.color = 0xFF2A1704.toInt()
        tp.shader = null
        c.drawText(value, cx, baseline, tp)
        tp.style = Paint.Style.FILL
        tp.shader = LinearGradient(
            0f, baseline + fm.ascent, 0f, baseline + fm.descent,
            intArrayOf(0xFFFFF3C4.toInt(), Palette.GOLD, 0xFFB0761B.toInt()),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawText(value, cx, baseline, tp)
        tp.shader = null
    }

    fun measure(value: String, size: Float, heavy: Boolean = true, spacing: Float = .04f): Float {
        tp.typeface = if (heavy) bold else medium
        tp.textSize = size
        tp.letterSpacing = spacing
        return tp.measureText(value)
    }

    fun multiline(
        c: Canvas, value: String, cx: Float, topY: Float, maxWidth: Float,
        size: Float, color: Int, lineHeight: Float = 1.42f
    ): Float {
        tp.typeface = medium
        tp.textSize = size
        tp.letterSpacing = .02f
        val lines = ArrayList<String>()
        var current = ""
        value.split(" ").forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (tp.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines += current
                current = word
            } else current = candidate
        }
        if (current.isNotEmpty()) lines += current
        lines.forEachIndexed { index, s ->
            text(c, s, cx, topY + index * size * lineHeight, size, color, Paint.Align.CENTER, heavy = false, spacing = .02f)
        }
        return lines.size * size * lineHeight
    }

    fun multilineLeft(
        c: Canvas, value: String, left: Float, centerY: Float, maxWidth: Float,
        size: Float, color: Int, maxLines: Int = 2
    ) {
        tp.typeface = medium
        tp.textSize = size
        tp.letterSpacing = .02f
        val lines = ArrayList<String>()
        var current = ""
        value.split(" ").forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (tp.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                if (lines.size + 1 >= maxLines) {
                    lines += current
                    current = ""
                    return@forEach
                }
                lines += current
                current = word
            } else if (current.isNotEmpty() || lines.size < maxLines) current = candidate
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current
        val lineHeight = size * 1.32f
        val startY = centerY - (lines.size - 1) * lineHeight / 2f
        lines.forEachIndexed { index, s ->
            text(c, s, left, startY + index * lineHeight, size, color, heavy = false, spacing = .02f, middle = true)
        }
    }

    // ----------------------------------------------------------------- sprites

    fun sprite(c: Canvas, bitmap: Bitmap, box: RectF, alpha: Int = 255) {
        val scale = min(box.width() / bitmap.width, box.height() / bitmap.height)
        val bw = bitmap.width * scale
        val bh = bitmap.height * scale
        fill.shader = null
        fill.alpha = alpha
        c.drawBitmap(
            bitmap, null,
            RectF(box.centerX() - bw / 2f, box.centerY() - bh / 2f, box.centerX() + bw / 2f, box.centerY() + bh / 2f),
            fill
        )
        fill.alpha = 255
    }

    fun socket(c: Canvas, cx: Float, cy: Float, radius: Float, tint: Int) {
        fill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.argb(120, Color.red(tint), Color.green(tint), Color.blue(tint)), 0xF0061229.toInt()),
            floatArrayOf(.2f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        line.shader = null
        line.color = Palette.GOLD
        line.strokeWidth = dp(1.6f)
        c.drawCircle(cx, cy, radius, line)
        line.color = Color.argb(110, 255, 240, 200)
        line.strokeWidth = dp(1f)
        c.drawCircle(cx, cy, radius - dp(4f), line)
    }

    fun lighten(color: Int, amount: Float) = Color.rgb(
        min(255, (Color.red(color) + (255 - Color.red(color)) * amount).toInt()),
        min(255, (Color.green(color) + (255 - Color.green(color)) * amount).toInt()),
        min(255, (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt())
    )

    fun darken(color: Int, amount: Float) = Color.rgb(
        (Color.red(color) * (1f - amount)).toInt(),
        (Color.green(color) * (1f - amount)).toInt(),
        (Color.blue(color) * (1f - amount)).toInt()
    )

    // ------------------------------------------------------------- primitives

    fun circle(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawCircle(cx, cy, radius, fill)
    }

    fun roundRect(c: Canvas, box: RectF, corner: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawRoundRect(box, corner, corner, fill)
    }

    fun badge(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, label: String? = null, glyph: Icon? = null) {
        glow(c, cx, cy, radius * 2f, color, 95)
        fill.shader = RadialGradient(
            cx, cy - radius * .45f, radius * 1.7f,
            intArrayOf(lighten(color, .5f), darken(color, .35f)), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        line.shader = null
        line.color = Palette.GOLD_LIGHT
        line.strokeWidth = dp(1.7f)
        c.drawCircle(cx, cy, radius, line)
        label?.let { text(c, it, cx, cy, radius * 1.05f, Palette.TEXT, Paint.Align.CENTER, spacing = .02f, middle = true) }
        glyph?.let {
            icon(c, RectF(cx - radius * .58f, cy - radius * .58f, cx + radius * .58f, cy + radius * .58f), it, Palette.GOLD_LIGHT)
        }
    }

    fun ribbon(c: Canvas, box: RectF, label: String, color: Int) {
        val notch = box.height() * .4f
        path.reset()
        path.moveTo(box.left, box.top)
        path.lineTo(box.right, box.top)
        path.lineTo(box.right - notch, box.centerY())
        path.lineTo(box.right, box.bottom)
        path.lineTo(box.left, box.bottom)
        path.lineTo(box.left + notch, box.centerY())
        path.close()
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            intArrayOf(lighten(color, .32f), color, darken(color, .42f)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)
        fill.shader = null
        line.shader = null
        line.color = Palette.GOLD_LIGHT
        line.strokeWidth = dp(1.8f)
        c.drawPath(path, line)
        text(c, label, box.centerX(), box.centerY(), box.height() * .44f, Palette.TEXT, Paint.Align.CENTER, spacing = .18f, middle = true)
    }

    fun timingTrack(
        c: Canvas, box: RectF, target: Float, normalHalf: Float, perfectHalf: Float, marker: Float
    ) {
        val corner = box.height() / 2f
        fill.shader = null
        fill.color = 0xFF04101F.toInt()
        c.drawRoundRect(box, corner, corner, fill)
        val inner = RectF(box.left + dp(2.4f), box.top + dp(2.4f), box.right - dp(2.4f), box.bottom - dp(2.4f))
        val innerCorner = inner.height() / 2f
        fill.shader = LinearGradient(
            0f, inner.top, 0f, inner.bottom,
            intArrayOf(0xFF7D211D.toInt(), 0xFF3A0D0B.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(inner, innerCorner, innerCorner, fill)
        fill.shader = null

        c.save()
        path.reset()
        path.addRoundRect(inner, innerCorner, innerCorner, Path.Direction.CW)
        c.clipPath(path)
        val normalLeft = inner.left + inner.width() * (target - normalHalf)
        val normalRight = inner.left + inner.width() * (target + normalHalf)
        fill.shader = LinearGradient(
            0f, inner.top, 0f, inner.bottom,
            intArrayOf(lighten(Palette.GREEN, .3f), darken(Palette.GREEN, .32f)), null, Shader.TileMode.CLAMP
        )
        c.drawRect(normalLeft, inner.top, normalRight, inner.bottom, fill)
        val perfectLeft = inner.left + inner.width() * (target - perfectHalf)
        val perfectRight = inner.left + inner.width() * (target + perfectHalf)
        fill.shader = LinearGradient(
            0f, inner.top, 0f, inner.bottom,
            intArrayOf(0xFFFFF0BE.toInt(), Palette.GOLD, Palette.GOLD_DEEP),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(perfectLeft, inner.top, perfectRight, inner.bottom, fill)
        fill.shader = null
        line.shader = null
        line.color = Color.argb(55, 255, 255, 255)
        line.strokeWidth = dp(1f)
        for (i in 1 until 16) {
            val x = inner.left + inner.width() * i / 16f
            c.drawLine(x, inner.top, x, inner.top + inner.height() * .28f, line)
        }
        c.restore()

        glow(c, (perfectLeft + perfectRight) / 2f, inner.centerY(), inner.height() * 1.45f, Palette.GOLD_LIGHT, 125)
        line.shader = null
        line.color = Palette.GOLD
        line.strokeWidth = dp(1.9f)
        c.drawRoundRect(box, corner, corner, line)

        val mx = inner.left + inner.width() * marker.coerceIn(0f, 1f)
        glow(c, mx, inner.centerY(), inner.height() * 1.7f, Color.WHITE, 185)
        fill.shader = null
        fill.color = Color.WHITE
        c.drawRoundRect(
            RectF(mx - dp(2.4f), box.top - dp(8f), mx + dp(2.4f), box.bottom + dp(8f)),
            dp(2f), dp(2f), fill
        )
        path.reset()
        path.moveTo(mx - dp(7.5f), box.top - dp(8f))
        path.lineTo(mx + dp(7.5f), box.top - dp(8f))
        path.lineTo(mx, box.top + dp(3f))
        path.close()
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(mx - dp(7.5f), box.bottom + dp(8f))
        path.lineTo(mx + dp(7.5f), box.bottom + dp(8f))
        path.lineTo(mx, box.bottom - dp(3f))
        path.close()
        c.drawPath(path, fill)
    }

    private companion object {
        val BOLT = floatArrayOf(.6f, .02f, .2f, .56f, .46f, .56f, .34f, .98f, .82f, .4f, .55f, .4f, .68f, .02f)
        val GEM = floatArrayOf(.3f, .08f, .7f, .08f, .96f, .38f, .5f, .96f, .04f, .38f)
        val ROOF = floatArrayOf(.5f, .04f, .96f, .34f, .04f, .34f)
        val ANVIL = floatArrayOf(
            .06f, .34f, .94f, .34f, .94f, .46f, .62f, .52f, .66f, .74f, .84f, .92f, .16f, .92f, .34f, .74f, .38f, .52f, .06f, .46f
        )
        val SPARK_V = floatArrayOf(.5f, 0f, .62f, .38f, .5f, 1f, .38f, .38f)
        val SPARK_H = floatArrayOf(0f, .5f, .38f, .38f, 1f, .5f, .38f, .62f)
        val ARROW = floatArrayOf(.1f, 1f, 1f, .75f, .5f, 0f)
    }
}
