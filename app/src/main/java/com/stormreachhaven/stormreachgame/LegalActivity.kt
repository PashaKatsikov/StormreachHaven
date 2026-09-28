package com.stormreachhaven.stormreachgame

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

class LegalActivity : Activity() {

    private lateinit var webView: WebView
    private var usedFallback = false
    private var fallbackUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.setFlags(1024, 1024)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Stormreach Haven"
        val remoteUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val asset = intent.getStringExtra(EXTRA_ASSET).orEmpty()
        // The bundled copies match the game's dark theme and work offline; the live pages
        // are only used when no local copy is shipped.
        val startUrl = if (asset.isNotEmpty()) "file:///android_asset/legal/$asset" else remoteUrl
        fallbackUrl = if (asset.isNotEmpty()) remoteUrl else ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#071525"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0A1E3C"))
            setPadding(dp(12), dp(18), dp(16), dp(14))
        }
        val back = TextView(this).apply {
            text = "  ‹  "
            textSize = 26f
            setTextColor(Color.parseColor("#FFE6A6"))
            setOnClickListener { finish() }
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 16f
            letterSpacing = .08f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(back)
        header.addView(heading)
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), 1)
        })

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#071525"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "mailto" || uri.scheme == "tel") {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progress.visibility = View.GONE
                    view.evaluateJavascript(DARK_THEME_CSS, null)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame && !usedFallback && fallbackUrl.isNotEmpty()) {
                        usedFallback = true
                        view.loadUrl(fallbackUrl)
                    }
                }
            }
        }

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(
            FrameLayout(this).apply { addView(webView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        webView.loadUrl(startUrl)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { finish() }
        }
        hideSystemBars()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Fallback for Android versions below 13")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else finish()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_ASSET = "asset"

        const val PRIVACY_URL = "https://stormreachhaven.store/privacy-policy.html"
        const val SUPPORT_URL = "https://stormreachhaven.store/support.html"

        /** Keeps remote pages readable if they ever replace the bundled dark-themed copies. */
        private const val DARK_THEME_CSS = """
            (function() {
              var style = document.createElement('style');
              style.textContent = 'html,body{background:#071525 !important;color:#d7e4f6 !important;}' +
                'h1,h2,h3,strong{color:#f3d27a !important;}' +
                'p,li,td,div,span,label{color:#d7e4f6 !important;}' +
                'a{color:#6ed7ff !important;}' +
                'input,textarea{background:#0a1a30 !important;color:#eef4ff !important;border-color:#3d628f !important;}';
              document.head.appendChild(style);
            })();
        """
    }
}
