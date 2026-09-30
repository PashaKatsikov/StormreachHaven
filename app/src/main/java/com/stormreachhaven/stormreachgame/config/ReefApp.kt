package com.stormreachhaven.stormreachgame.config

import android.app.Application
import com.stormreachhaven.stormreachgame.BuildConfig
import com.stormreachhaven.stormreachgame.prefs.Prefs
import com.stormreachhaven.stormreachgame.screens.Trace
import com.stormreachhaven.stormreachgame.screens.UrlGuard
import com.stormreachhaven.stormreachgame.push.AttrHub
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Application entry. Two responsibilities: initialise Firebase (best-effort)
 * and prime the AppsFlyer SDK before any activity runs.
 *
 * See `.cursor/rules/kotlin_launch_flow.mdc` for why the split between prime
 * and start belongs where it does — moving it out of the Application is one
 * of the three ways to lose attribution (#19 in the pitfalls).
 */
class ReefApp : Application() {

    lateinit var trackingDispatch: AttrHub
        private set

    override fun onCreate() {
        super.onCreate()

        try {
            FirebaseApp.initializeApp(this)
            val fac = if (BuildConfig.DEBUG)
                DebugAppCheckProviderFactory.getInstance()
            else
                PlayIntegrityAppCheckProviderFactory.getInstance()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(fac)
        } catch (e: Exception) {
            Trace.w(TAG, "Firebase not configured — gray flow will still try the config POST", e)
        }

        // Kick FCM registration at process start, independently of the launch
        // flow. When the app is opened with no network — the offline-first case —
        // the token request fails, but keeping auto-init on lets Firebase's own
        // backoff re-register the moment connectivity returns, so push is not lost
        // for a session that merely started offline. The token is cached for the
        // config POST as soon as it arrives.
        try {
            val fm = FirebaseMessaging.getInstance()
            fm.isAutoInitEnabled = true
            fm.token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) runCatching { Prefs(this).fcmToken = token }
            }
        } catch (e: Exception) {
            Trace.w(TAG, "FCM token prefetch skipped", e)
        }

        // Warn once if the operator did not fill in gray.allowedHosts.
        UrlGuard.warnIfMissing()

        trackingDispatch = AttrHub(this)
        trackingDispatch.prime()
    }

    private companion object { const val TAG = "ReefApp" }
}
