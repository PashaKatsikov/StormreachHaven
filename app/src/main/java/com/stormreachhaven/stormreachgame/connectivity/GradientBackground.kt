package com.stormreachhaven.stormreachgame.connectivity

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared look for the code-drawn system screens (no-internet, push permission):
 * a storm-toned gradient fill and a centred title/subtitle block that stays
 * legible in both orientations. Keeping it here means both screens share one
 * palette and one set of type sizes.
 */
internal object GradientBackground {

    /** Diagonal storm gradient in the app's deep-blue / gold key. */
    fun storm(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.parseColor("#0C315C"),
            Color.parseColor("#08203C"),
            Color.parseColor("#040E1C")
        )
    ).apply { gradientType = GradientDrawable.LINEAR_GRADIENT }

    /**
     * A centred title + subtitle, sized for both orientations. Returned already
     * laid out for a [FrameLayout] parent, centred with side padding so long
     * lines wrap instead of touching the edges.
     */
    fun textBlock(ctx: Context, title: String, subtitle: String): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(0), dp(32), dp(0))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#FFD45A"))
            textSize = 23f
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setTypeface(Typeface.DEFAULT_BOLD)
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#66000000"))
        }
        column.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitleView = TextView(ctx).apply {
            text = subtitle
            setTextColor(Color.parseColor("#C2D2E6"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        column.addView(
            subtitleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return column
    }
}
