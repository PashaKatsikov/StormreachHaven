package com.stormreachhaven.stormreachgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * Branded loading / splash screen with an animated indicator.
 *
 * Two modes:
 *  - timed: bar fills over [durationMs] then calls [onComplete].
 *  - indeterminate: bar eases toward ~92% with a moving shimmer and never
 *    "completes" on a timer (used by TideRouter while routing runs, which
 *    can take a variable amount of time). The caption dots keep animating.
 *    When routing is done, [complete] runs the bar out to 100% and only then
 *    hands over — the user is never moved on by a bar that stopped at 70%, and
 *    never left staring at a full one either.
 *
 * TODO(you): this template draws a code-only placeholder background. Replace
 *   [drawBackground] with your branded artwork — load orientation-aware images
 *   (e.g. R.drawable.loading_portrait / R.drawable.loading_landscape) and draw
 *   portrait as center-crop, landscape as fit (so the title is never cropped).
 *   Per TZ the loading screen must adapt to portrait AND landscape and finish
 *   within ~10s on normal networks.
 */
class TideLoader(
    context: Context,
    private val durationMs: Long = 2400L,
    private val indeterminate: Boolean = false,
    private val onComplete: () -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accent = 0xFFFFD45A.toInt() // Stormreach Haven gold

    private val bitmaps = HashMap<Int, Bitmap?>()
    private fun cached(res: Int): Bitmap? =
        bitmaps.getOrPut(res) {
            runCatching { BitmapFactory.decodeResource(resources, res) }.getOrNull()
        }

    private var startTime = 0L
    private var finished = false

    private var closingAt = 0L
    private var closingFrom = 0f
    private var onClosed: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /**
     * Routing is done: run the bar out to 100%, hold for a beat so the eye registers
     * it, then hand over. Calling this twice is harmless.
     */
    fun complete(after: () -> Unit) {
        if (closingAt != 0L) return
        onClosed = after
        closingFrom = shownProgress
        closingAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private var shownProgress = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        drawBackground(canvas, w, h)

        val elapsed = SystemClock.uptimeMillis() - startTime

        // Landscape is the short-side orientation, so the height-derived bar and
        // caption come out small — doubled there per spec so they read at a glance.
        val landscape = w > h
        val textScale = if (landscape) 2f else 1f
        val captionY  = if (landscape) h * 0.855f else h * 0.885f

        // Caption dots — always animating (never frozen).
        val dots = ".".repeat(((elapsed / 400L) % 4L).toInt())
        textPaint.textSize = h * 0.030f * textScale
        textPaint.color = accent
        textPaint.setShadowLayer(h * 0.006f, 0f, h * 0.003f, Color.BLACK)
        canvas.drawText("Loading$dots", w / 2f, captionY, textPaint)
        textPaint.clearShadowLayer()

        if (indeterminate) {
            val progress = if (closingAt != 0L) {
                val run = ((SystemClock.uptimeMillis() - closingAt).toFloat() / CLOSE_MS)
                    .coerceIn(0f, 1f)
                closingFrom + (1f - closingFrom) * run
            } else {
                val t = elapsed / 1000f
                (0.92f * (1f - Math.exp((-t / 1.1f).toDouble()).toFloat())).coerceIn(0f, 0.95f)
            }
            shownProgress = progress
            drawLoadingBar(canvas, w, h, progress, shimmer = true, shimmerPhase = elapsed)
            if (progress >= 1f && !finished) {
                finished = true
                val handOver = onClosed
                onClosed = null
                postDelayed({ handOver?.invoke() }, HOLD_MS)
                return
            }
        } else {
            val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            shownProgress = progress
            drawLoadingBar(canvas, w, h, progress, shimmer = false, shimmerPhase = 0L)
            if (progress >= 1f && !finished) {
                finished = true
                post { onComplete() }
                return
            }
        }
        postInvalidateOnAnimation()
    }

    /** Branded splash artwork, orientation-aware, drawn CENTER_CROP. */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val landscape = w > h
        val res = if (landscape) R.drawable.reef_loading_landscape
                  else          R.drawable.reef_loading_portrait
        val bmp = cached(res)
        if (bmp == null) {
            paint.color = 0xFF071525.toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        canvas.drawBitmap(
            bmp, null,
            RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
            null
        )
    }

    private fun drawLoadingBar(
        canvas: Canvas, w: Float, h: Float, progress: Float,
        shimmer: Boolean, shimmerPhase: Long
    ) {
        val landscape = w > h
        val barW = w * 0.62f
        val barH = h * 0.022f * (if (landscape) 2f else 1f)
        val x0 = (w - barW) / 2f
        val y0 = h * 0.915f
        val r = barH / 2f

        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)

        if (progress > 0f) {
            val fillW = barW * progress
            paint.shader = LinearGradient(
                x0, y0, x0 + barW, y0,
                0xFFE53935.toInt(), accent, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(x0, y0, x0 + fillW, y0 + barH, r, r, paint)
            paint.shader = null

            if (shimmer && fillW > barH) {
                val sw = barW * 0.18f
                val cycle = 1400f
                val phase = (shimmerPhase % cycle.toLong()) / cycle
                val cx = x0 + (fillW + sw) * phase - sw
                val left = cx.coerceIn(x0, x0 + fillW)
                val right = (cx + sw).coerceIn(x0, x0 + fillW)
                if (right > left) {
                    paint.shader = LinearGradient(
                        left, y0, right, y0, 0x00FFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(left, y0, right, y0 + barH, r, r, paint)
                    paint.shader = null
                }
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0035f
        paint.color = accent
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    private companion object {
        /** How long the bar takes to run out once routing is done. */
        const val CLOSE_MS = 280f

        /** Beat between a full bar and the next screen. Longer feels like a stall. */
        const val HOLD_MS = 420L
    }
}
