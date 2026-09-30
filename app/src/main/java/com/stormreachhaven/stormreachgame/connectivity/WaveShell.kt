package com.stormreachhaven.stormreachgame.connectivity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.stormreachhaven.stormreachgame.BuildConfig
import com.stormreachhaven.stormreachgame.Immersive
import com.stormreachhaven.stormreachgame.net.Env
import com.stormreachhaven.stormreachgame.screens.Trace
import com.stormreachhaven.stormreachgame.screens.UserAgent
import com.stormreachhaven.stormreachgame.screens.PushBusReef
import com.stormreachhaven.stormreachgame.prefs.Prefs
import com.stormreachhaven.stormreachgame.attribution.NetMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell.
 *
 *  - Black background everywhere (no Android system flash on load / page exit).
 *  - Safe-area paddings: top inset in portrait, left+right insets in landscape
 *    (handles notch / cutout cameras).
 *  - Instant NoCurrentScreen navigation on connectivity loss — no DNS probe.
 *  - Keyboard handled by [KeyboardTide] (the view slides, it never resizes) plus the
 *    safe-area CSS kill injection.
 *  - A loading cover over redirect hops and failed loads, so the user only ever sees
 *    a finished page — never an intermediate hop or the WebView's own error page.
 *  - Cold + warm push URL routing through Intent extras / onNewIntent.
 *  - User-Agent ends with "appid/<bundleId> appname/<AppName>".
 */
class WaveShell : AppCompatActivity() {

    private lateinit var wv: WebView
    private lateinit var container: FrameLayout
    private lateinit var vault: Prefs
    private lateinit var wire: NetMon
    private lateinit var keyboard: KeyboardTide
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Last main-frame URL that actually settled. What a renderer recovery reloads. */
    private var lastMainFrameUrl: String? = null

    /**
     * Deepest main-frame URL seen, settled or not. A redirect loop is resumed
     * from here rather than from the last settled page — restarting the chain
     * from its entry point only walks into the same loop again (pitfalls #30).
     */
    private var deepestHop: String? = null

    private var redirectRetries = 0
    /** One fallback to the configured entry point per settled page. */
    private var entryPointRetried = false
    /** Full chain restarts from the entry point once the per-hop budget is spent. */
    private var fullRestarts = 0
    private var rendererRecoveries = 0

    /** A failed load still reaches onPageFinished; without this it resets the budget. */
    private var loadFailed = false
    /** True once one page of this session has rendered — gates the cover. */
    private var firstPageSettled = false
    /** Keeps the cover raised across the reload a retry queues up. */
    private var retryPending = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fc = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        fc.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: arrayOf()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Prefs(applicationContext)
        wire  = NetMon(applicationContext)
        PushBusReef.shellAlive = true

        // Background stays black at all times — windowBackground in the theme is black,
        // and we keep the root view black too.
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        setContentView(container)
        applyInsets()

        keyboard = KeyboardTide(window.decorView, vault)
        keyboard.install()
        recreateWebView()

        hideSystemUi()
        enableNotchCutout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack()
            }
        })

        // Choose the initial URL: warm push > intent extra > saved.
        val warmPush = intent.takeIf { it.getBooleanExtra(EXTRA_PUSH_WARM, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
        val coldPush = vault.consumeColdPushUrl()
        val initial  = warmPush
            ?: coldPush
            ?: intent.getStringExtra(EXTRA_STREAM_URL)
            ?: vault.destinationUrl

        if (initial.isNullOrBlank()) {
            Trace.w(TAG, "No URL to load — finishing")
            finish(); return
        }
        Trace.i(TAG, "loading initial URL (warm=${warmPush != null}, cold=${coldPush != null})")
        wv.loadUrl(initial)

        // Connectivity monitoring — react instantly on OS callback.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) {
                    Trace.i(TAG, "Connectivity lost (callback) → NoCurrentScreen")
                    goOffline()
                }
            }
        }

        // Heartbeat — covers the case where the page is already loaded and the user
        // turns off the internet: no WebView request fails, so we actively probe.
        scope.launch {
            while (true) {
                delay(Env.heartbeatMs)
                if (navigatedOffline) continue
                if (!wire.isConnected()) {
                    Trace.i(TAG, "Heartbeat: no network → NoCurrentScreen")
                    goOffline()
                }
            }
        }

        scope.launch {
            delay(Env.safeAreaDelayMs)
            injectSafeAreaKill()
        }
    }

    /** Builds the WebView, puts it in the container and hooks everything to it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun recreateWebView() {
        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                userAgentString = buildUserAgent()
                // Performance + compatibility.
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // Popups stay in this view. Asking for real second windows is what
                // makes the WebView demand a host for them and throw when it cannot
                // get one ("Parent WebView cannot host its own popup window").
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
            }
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient   = buildClient()
        wv.webChromeClient = buildChromeClient()
        keyboard.bind(wv)
    }

    // ── Loading cover ───────────────────────────────────────────────────

    private var cover: View? = null
    private var coverJob: Job? = null

    /**
     * Opaque black fill with a centred spinner, raised over the WebView for the
     * duration of any main-frame navigation. A redirect chain re-raises it on
     * every hop before a drop can land, so its intermediate pages — and any
     * browser ERR_* page a hop renders — never surface; the cover is only dropped
     * when a page actually settles (onPageFinished / progress 100). Reusing the
     * existing cover keeps it steady across the hops instead of blinking.
     */
    private fun raiseCover() {
        coverJob?.cancel()
        coverJob = null
        val existing = cover
        if (existing != null) {
            existing.animate().cancel()
            existing.alpha = 1f
            return
        }
        val fresh = FrameLayout(this).apply {
            setBackgroundColor(COVER_SCRIM)
            isClickable = true
            addView(
                android.widget.ProgressBar(this@WaveShell).apply {
                    isIndeterminate = true
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        }
        cover = fresh
        container.addView(
            fresh,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // A page that never reports back must not hold the screen for good.
        scope.launch {
            delay(COVER_MAX_MS)
            if (cover === fresh) {
                Trace.w(TAG, "Loading cover timed out")
                dropCover(0L)
            }
        }
    }

    /**
     * @param after grace before the page is handed back. A redirect hop finishes and
     *   starts the next load within a frame or two, and this is what keeps the cover
     *   from blinking off and on between them.
     */
    private fun dropCover(after: Long = COVER_LINGER_MS) {
        val current = cover ?: return
        coverJob?.cancel()
        coverJob = scope.launch {
            delay(after)
            if (cover !== current) return@launch
            cover = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                container.removeView(current)
            }.start()
        }
    }

    // ── WebView clients ────────────────────────────────────────────────

    private var pageStartMs = 0L

    private fun buildClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            val scheme = u.substringBefore(':').lowercase()
            return when {
                scheme in WEB_SCHEMES -> {
                    if (req.isForMainFrame) deepestHop = u
                    false  // load inside this WebView
                }
                scheme == "intent" -> { openIntentUri(u); true }
                // Everything else is an app link: banks, wallets, messengers, stores.
                // Handing it to the WebView would only produce ERR_UNKNOWN_URL_SCHEME,
                // and the list of schemes worth knowing about has no end.
                else -> { openExternally(u); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            pageStartMs = System.currentTimeMillis()
            loadFailed = false
            retryPending = false
            keyboard.forget()
            // shouldOverrideUrlLoading does not see every server-side 30x, so the
            // URL the engine actually committed to is the other half of the trail.
            if (url != BLANK) deepestHop = url
            // Every main-frame navigation is covered by an opaque black scrim with a
            // centred spinner. A redirect chain re-raises it on each hop before any
            // drop, so its intermediate pages — and any browser ERR_* page a hop
            // renders — stay hidden; only the settled destination is revealed.
            if (url != BLANK) raiseCover()
            Trace.i(TAG, "onPageStarted")
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            loadFailed = true
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.description.toString() else ""
            Trace.w(TAG, "main-frame error $code on ${req.url.host}")

            // A custom scheme reaching this point was already handed to the system;
            // the page behind it is still fine, so give it straight back.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover(0L)
                return
            }

            // From here the navigation is genuinely failing, and Chromium is about
            // to commit its own ERR_* page. Raise the black cover first so that
            // page can never be seen while we recover.
            raiseCover()

            val isLoop = code == -9 || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true)
            if (isLoop) {
                handleRedirectLoop(view, req.url.toString())
                return
            }

            val isNetErr = code in setOf(-2, -6, -7, -8, -11)
            if (isNetErr || !wire.isConnected()) {
                view.stopLoading()
                view.loadUrl(BLANK)
                goOffline()
                return
            }

            // Anything else: wipe Chromium's error page to blank (black, under the
            // cover) rather than revealing it, then reveal the clean black.
            runCatching { view.stopLoading() }
            view.loadUrl(BLANK)
            dropCover(0L)
        }

        override fun onPageFinished(view: WebView, url: String) {
            Trace.i(TAG, "onPageFinished")
            if (loadFailed || url == BLANK) return
            redirectRetries = 0
            entryPointRetried = false
            fullRestarts = 0
            retryPending = false
            firstPageSettled = true
            lastMainFrameUrl = url
            deepestHop = url
            injectSafeAreaKill()
            view.evaluateJavascript(keyboard.script, null)
            dropCover()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail
        ): Boolean {
            Trace.w(TAG, "render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== wv) {
                runCatching { view.destroy() }
                return true
            }
            if (rendererRecoveries >= MAX_RENDERER_RECOVERIES) {
                Trace.w(TAG, "renderer recovery budget exhausted → NoCurrentScreen")
                goOffline()
                return true
            }
            rendererRecoveries++
            replaceWebView()
            return true
        }
    }

    /**
     * ERR_TOO_MANY_REDIRECTS. Chromium gives up after 20 hops and affiliate
     * chains are routinely longer, so this is an ordinary condition rather than
     * a failure — the chain has to be resumed, not restarted.
     *
     * Three things this gets right that the obvious version does not:
     *
     *  - It resumes from [deepestHop]. Reloading the entry point walks the same
     *    hops again and burns the budget on the identical loop. `lastMainFrameUrl`
     *    is the wrong field for this: `onPageFinished` overwrites it with the
     *    page that settled, so by error time it names the chain's start.
     *  - It posts the reload instead of calling `loadUrl` from inside the
     *    callback. The engine is still unwinding the failed navigation at that
     *    point and swallows or defers a re-entrant load — which is where the
     *    multi-second stalls between attempts came from.
     *  - When the budget is gone it retries the entry point, then restarts the
     *    whole chain a couple of times, and if it still cannot resolve it wipes
     *    Chromium's ERR_TOO_MANY_REDIRECTS page to blank — the user never sees it.
     *
     * The black cover is already up (raised in onReceivedError) for the whole of
     * this, so every hop and every retry happens behind it; only a settled page
     * is ever revealed.
     */
    private fun handleRedirectLoop(view: WebView, failedUrl: String) {
        if (redirectRetries < Env.redirectRetryMax) {
            redirectRetries++
            retryPending = true
            val resumeAt = deepestHop ?: failedUrl
            Trace.i(TAG, "redirect loop, resuming attempt $redirectRetries")
            postLoad(view, resumeAt)
            return
        }

        // Budget spent. The chain itself is stuck; the entry point the backend
        // named usually still resolves, and cookies picked up along the way are
        // often what the chain was missing.
        val entryPoint = vault.destinationUrl
        if (!entryPointRetried && !entryPoint.isNullOrBlank() && entryPoint != deepestHop) {
            entryPointRetried = true
            retryPending = true
            Trace.w(TAG, "redirect budget spent → retrying the configured entry point")
            postLoad(view, entryPoint)
            return
        }

        // Resume and single entry-point retry are both spent. Give the whole chain
        // a fresh start from the entry point a couple of times — affiliate loops
        // routinely clear once enough cookies are set — resetting the budget each
        // time so it gets a real run.
        if (fullRestarts < MAX_FULL_RESTARTS && !entryPoint.isNullOrBlank()) {
            fullRestarts++
            redirectRetries = 0
            entryPointRetried = false
            deepestHop = null
            retryPending = true
            Trace.w(TAG, "redirect budget spent → full restart $fullRestarts from entry point")
            runCatching { view.stopLoading() }
            postLoad(view, entryPoint)
            return
        }

        // Truly unresolvable. Never surface Chromium's ERR_TOO_MANY_REDIRECTS page:
        // wipe it to blank (black, matching the cover) and reveal that instead.
        Trace.w(TAG, "redirect chain unresolvable — wiping error page to blank")
        retryPending = false
        runCatching { view.stopLoading() }
        view.loadUrl(BLANK)
        dropCover(0L)
    }

    /**
     * A load queued out of a WebViewClient callback. The short pause is dead
     * time in the middle of a navigation, not a delay the user can feel.
     */
    private fun postLoad(view: WebView, url: String) {
        view.postDelayed({
            if (!isFinishing && !isDestroyed) view.loadUrl(url)
        }, RETRY_PAUSE_MS)
    }

    /**
     * Builds a fresh WebView after a renderer death and puts the last good page back.
     * The dead one cannot be reused for anything, including being asked what it was
     * showing, so [lastMainFrameUrl] is what there is to go on.
     */
    private fun replaceWebView() {
        val resumeAt = lastMainFrameUrl ?: vault.destinationUrl ?: return
        val dead = wv
        container.removeView(dead)
        runCatching { dead.destroy() }
        recreateWebView()
        wv.loadUrl(resumeAt)
    }

    private fun buildChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Backstop for a page that reports progress but never a finished load.
            // about:blank is only ever loaded on the way out to the offline screen,
            // so its progress says nothing about the page the user is waiting for.
            if (newProgress < 100 || view.url == BLANK) return
            // Chromium's ERR_* page is a page too and loads to 100%. Without this
            // guard, progress-100 on that error page dropped the cover and let it
            // flash between hops. A failed load, or one with a retry queued, keeps
            // the cover up — only a genuine page ever lifts it.
            if (loadFailed || retryPending) return
            dropCover()
        }

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(arrayOf())
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }
    }

    // ── Links the WebView cannot take ───────────────────────────────────

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        launchOrIgnore(intent)
    }

    /**
     * intent:// URIs name a target app and usually carry a browser_fallback_url, so
     * there are three things to try before the user is left looking at nothing.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (launchOrIgnore(parsed)) return
        // The named app may be missing while some other app handles the scheme.
        parsed.`package` = null
        if (launchOrIgnore(parsed)) return
        if (!fallback.isNullOrBlank()) wv.loadUrl(fallback)
    }

    private fun launchOrIgnore(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    // ── Navigation ──────────────────────────────────────────────────────

    @Volatile private var navigatedOffline = false

    private fun goOffline() {
        if (navigatedOffline) return
        navigatedOffline = true
        val cur = lastMainFrameUrl ?: wv.url
        try { wv.stopLoading(); wv.loadUrl(BLANK) } catch (_: Exception) {}
        startActivity(Intent(this, NoCurrentScreen::class.java).apply {
            if (!cur.isNullOrBlank()) putExtra(NoCurrentScreen.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigatedOffline = false

        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank() && com.stormreachhaven.stormreachgame.screens.UrlGuard.accepts(url)) {
                Trace.i(TAG, "warm push → loading")
                wv.loadUrl(url)
                return
            }
        }

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val current = wv.url
        val target = streamUrl ?: vault.destinationUrl
        if (!target.isNullOrBlank() &&
            (current.isNullOrBlank() || current == BLANK || current != target)) {
            Trace.i(TAG, "onNewIntent → reloading target")
            wv.loadUrl(target)
        }
    }

    // ── Insets / safe area ──────────────────────────────────────────────

    /**
     * Apply orientation-aware padding so the WebView never sits under the camera
     * notch / cutout.
     *   portrait  → top inset only
     *   landscape → left + right insets (cutout on either side)
     */
    private fun applyInsets() {
        container.setOnApplyWindowInsetsListener { v, insets ->
            val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                insets.displayCutout else null
            // Landscape pads ONLY for the camera cutout, never for the system bars.
            // Opening the keyboard makes the navigation bar appear, and folding its
            // inset into the padding here is what shrank the WebView out from under
            // the page. Left out, the bar simply draws over the WebView (it is a
            // transient, swipe-shown bar) while the notch inset is still respected.
            val topPad   = if (!isLandscape) (cutout?.safeInsetTop ?: 0).coerceAtLeast(insetTop(insets)) else 0
            val leftPad  = if (isLandscape)  (cutout?.safeInsetLeft  ?: 0) else 0
            val rightPad = if (isLandscape)  (cutout?.safeInsetRight ?: 0) else 0
            v.setPadding(leftPad, topPad, rightPad, 0)
            insets
        }
        container.requestApplyInsets()
    }

    private fun insetTop(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).top else 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        container.requestApplyInsets()
        keyboard.remeasure()
        Immersive.apply(this)
    }

    private fun hideSystemUi() = Immersive.apply(this)

    private fun enableNotchCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // ── JS injections ───────────────────────────────────────────────────

    /**
     * Safe-area CSS kill. The window already pads for the cutout, so a page that
     * also honours `env(safe-area-inset-*)` would leave a second empty band on
     * top of ours. Zeroing the variables removes that band.
     *
     * What it must not do is lay a finger on the page's own box model. An
     * earlier version zeroed `padding-left`, `padding-right` and `margin` on
     * `html, body, #__nuxt, #app, #root` — but sites build their gutters with
     * exactly those declarations, so the whole layout got squeezed flat against
     * both edges (pitfalls #10). Only `padding-top`, and only on the chrome
     * wrappers that are known to add a status-bar offset of their own.
     */
    private fun injectSafeAreaKill() {
        val sentinel = BuildConfig.JS_SAFE_AREA_SENTINEL
        val running  = sentinel + "R"
        wv.evaluateJavascript("""
            (function(){
              if(window.$running) return; window.$running = true;
              var CSS_ID = '$sentinel';
              var CSS_TEXT =
                ':root{' +
                  '--safe-area-inset-top:0px!important;' +
                  '--safe-area-inset-right:0px!important;' +
                  '--safe-area-inset-bottom:0px!important;' +
                  '--safe-area-inset-left:0px!important;' +
                  '--sat:0px!important;--sar:0px!important;' +
                  '--sab:0px!important;--sal:0px!important;' +
                  '--safe-top:0px!important;--safe-right:0px!important;' +
                  '--safe-bottom:0px!important;--safe-left:0px!important;' +
                '}' +
                '.gameview-mobile-header,.app-header{' +
                  'padding-top:0!important;' +
                '}';
              function apply(){
                var head = document.head || document.documentElement;
                if (!head) return;
                var m = document.querySelector('meta[name="viewport"]');
                if (m && !/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content') || '')) {
                  var c = (m.getAttribute('content') || '')
                    .replace(/,?\s*viewport-fit\s*=\s*\w+/ig, '').trim();
                  m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
                }
                var s = document.getElementById(CSS_ID);
                if (!s) {
                  s = document.createElement('style');
                  s.id = CSS_ID;
                  head.appendChild(s);
                }
                if (s.textContent !== CSS_TEXT) s.textContent = CSS_TEXT;
                if (head.lastElementChild !== s) head.appendChild(s);
              }
              apply();
              ['pushState','replaceState'].forEach(function(fn){
                var orig = history[fn];
                history[fn] = function(){
                  var r = orig.apply(this, arguments);
                  setTimeout(apply, 80);
                  setTimeout(apply, 400);
                  return r;
                };
              });
              window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
              setInterval(apply, 2500);
            })();
        """.trimIndent(), null)
    }

    // ── User agent ──────────────────────────────────────────────────────

    private fun buildUserAgent(): String = UserAgent.value

    override fun onStart() {
        super.onStart()
        navigatedOffline = false
        PushBusReef.onWarmUrl = { url ->
            runOnUiThread {
                Trace.i(TAG, "PushBusReef warm URL → loading")
                try { wv.loadUrl(url) } catch (_: Exception) {}
            }
        }
        PushBusReef.consume()?.let { url ->
            Trace.i(TAG, "queued push URL → loading")
            runCatching { wv.loadUrl(url) }
        }
    }

    override fun onStop() {
        if (PushBusReef.onWarmUrl != null) PushBusReef.onWarmUrl = null
        super.onStop()
    }

    override fun onDestroy() {
        if (PushBusReef.onWarmUrl != null) PushBusReef.onWarmUrl = null
        PushBusReef.shellAlive = false
        scope.cancel()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL   = "push_url"
        const val EXTRA_PUSH_WARM  = "push_warm"
        private const val TAG = "WaveShell"

        /** Everything the WebView itself can take. Anything else belongs to an app. */
        private val WEB_SCHEMES =
            setOf("http", "https", "about", "data", "blob", "file", "javascript")

        private const val BLANK = "about:blank"

        /** Long enough to bridge one redirect hop, short enough not to be felt. */
        private const val COVER_LINGER_MS = 120L

        /** No page may hold the screen longer than this, finished or not. */
        private const val COVER_MAX_MS = 20_000L

        /** Renderer recoveries per Activity — beyond this we go offline. */
        private const val MAX_RENDERER_RECOVERIES = 3

        /** Full redirect-chain restarts before the error page is wiped to blank. */
        private const val MAX_FULL_RESTARTS = 2

        /** Opaque black fill under the spinner while a page / redirect chain resolves. */
        private const val COVER_SCRIM = 0xFF000000.toInt()

        /** Pause before a queued redirect-loop retry. Long enough to let the
         *  engine finish unwinding the failed navigation, short enough to be
         *  invisible. */
        private const val RETRY_PAUSE_MS = 60L
    }
}
