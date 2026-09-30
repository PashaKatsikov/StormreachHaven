package com.stormreachhaven.stormreachgame.connectivity

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.stormreachhaven.stormreachgame.R
import com.stormreachhaven.stormreachgame.config.TideRouter
import com.stormreachhaven.stormreachgame.attribution.NetMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * No-internet screen. A storm-toned gradient with a title, a one-line hint and a
 * RETRY button — laid out programmatically so it reads correctly in portrait and
 * landscape. On retry, checks real connectivity and returns to TideRouter or
 * WaveShell.
 */
class NoCurrentScreen : AppCompatActivity() {

    private lateinit var wire: NetMon
    private val scope = CoroutineScope(Dispatchers.Main)
    private var retryBtn: TextView? = null
    private var returnUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wire = NetMon(applicationContext)
        returnUrl = intent.getStringExtra(EXTRA_RETURN_URL)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientBackground.storm()
        }

        root.addView(
            GradientBackground.textBlock(
                this,
                title = "NO INTERNET CONNECTION",
                subtitle = "Check your connection and try again"
            )
        )

        val btn = buildRetryButton()
        retryBtn = btn
        btn.setOnClickListener { tryRetry() }
        val lp = FrameLayout.LayoutParams(dpToPx(200), dpToPx(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dpToPx(48)
        btn.layoutParams = lp
        root.addView(btn)

        setContentView(root)
        com.stormreachhaven.stormreachgame.Immersive.apply(this)

        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (online) tryRetry()
            }
        }
    }

    private fun tryRetry() {
        retryBtn?.text = "Connecting..."
        retryBtn?.isEnabled = false
        scope.launch {
            val ok = wire.hasRealInternet()
            if (ok) {
                val next = if (!returnUrl.isNullOrBlank()) {
                    Intent(this@NoCurrentScreen, WaveShell::class.java)
                        .putExtra(WaveShell.EXTRA_STREAM_URL, returnUrl)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TOP)
                } else {
                    // With no page to go back to, this is a first launch that began
                    // offline: the router runs its decision from the top, which is
                    // the first time AppsFlyer is asked anything. Patching state
                    // here instead would settle the install as organic.
                    Intent(this@NoCurrentScreen, TideRouter::class.java)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(next)
                finish()
            } else {
                retryBtn?.text = "RETRY"
                retryBtn?.isEnabled = true
            }
        }
    }

    private fun buildRetryButton(): TextView = TextView(this).apply {
        text = "RETRY"
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(null, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.reef_btn_accept)
        setTextColor(Color.parseColor("#1A0A00"))
        setPadding(0, 0, 0, 0)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RETURN_URL = "offline_return_url"
    }
}
