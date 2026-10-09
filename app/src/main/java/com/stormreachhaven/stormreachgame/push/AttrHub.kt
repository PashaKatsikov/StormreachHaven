package com.stormreachhaven.stormreachgame.push

import android.app.Activity
import android.content.Context
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.stormreachhaven.stormreachgame.BuildConfig
import com.stormreachhaven.stormreachgame.net.Env
import com.stormreachhaven.stormreachgame.screens.Trace
import com.stormreachhaven.stormreachgame.screens.UserAgent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AppsFlyer attribution, in two strokes. See `.cursor/rules/kotlin_launch_flow.mdc`
 * before touching anything here — every line below is load-bearing.
 *
 * [prime] wires the callbacks and belongs in the Application, where the SDK
 * expects to be introduced: it registers activity-lifecycle callbacks the SDK
 * uses to notice the app is in the foreground. Doing that with an activity
 * already on screen means the SDK never notices, and the launch sits queued
 * until the *next* activity appears. It puts nothing on the wire.
 *
 * [ignite] is the call that speaks to AppsFlyer, and it must not run before
 * the caller has seen a connection.
 */
class AttrHub(private val ctx: Context) {

    @Volatile private var attribution = CompletableDeferred<Map<String, Any?>>()
    @Volatile private var settled: Map<String, Any?>? = null

    private val deepLinkParams = mutableMapOf<String, Any?>()
    private val deepLink = CompletableDeferred<Unit>()

    private var primed = false
    private var started = false

    @Volatile private var reasked = false
    private var reaskedAt = 0L

    /** Structured scope for background work that outlives a single Activity. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gcdHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Env.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(Env.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    fun prime() {
        if (primed) return
        primed = true

        val key = Env.resolveTrackerKey()
        if (key.isBlank()) {
            Trace.i(TAG, "dev key not configured — attribution resolves empty")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }

        val wired = runCatching {
            AppsFlyerLib.getInstance().apply {
                setDebugLog(BuildConfig.DEBUG)     // never log in release
                subscribeForDeepLink(deepLinkListener)
                init(key, conversionListener, ctx.applicationContext)
            }
        }
        if (wired.isFailure) {
            Trace.w(TAG, "SDK would not wire up: ${wired.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
        }
    }

    fun ignite(host: Activity) {
        prime()
        if (started || Env.resolveTrackerKey().isBlank()) return
        started = true

        val lit = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (lit.isFailure) {
            Trace.w(TAG, "SDK would not start: ${lit.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }
        Trace.i(TAG, "SDK started from ${host.javaClass.simpleName}")
    }

    fun retrace(host: Activity) {
        if (!started) { ignite(host); return }
        val last = settled ?: return
        if (last.isNotEmpty()) return

        settled = null
        reasked = true
        reaskedAt = System.currentTimeMillis()
        attribution = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        Trace.i(TAG, "attribution asked again now the link is up")
    }

    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> = coroutineScope {
        val link = async { withTimeoutOrNull(Env.deepLinkWaitMs) { deepLink.await() } }
        val data = async { awaitConversion(timeoutMs) }
        link.await()
        data.await()
    }

    private suspend fun awaitConversion(timeoutMs: Long): Map<String, Any?> {
        val window = if (reasked) minOf(timeoutMs, RETRACE_WAIT_MS) else timeoutMs
        val fromSdk = withTimeoutOrNull(window) { attribution.await() } ?: emptyMap()
        if (fromSdk.isNotEmpty()) return fromSdk

        if (reasked) {
            val waited = System.currentTimeMillis() - reaskedAt
            if (waited < RETRACE_WAIT_MS) delay(RETRACE_WAIT_MS - waited)
        }

        val fromGcd = fetchGcd()
        if (fromGcd.isNullOrEmpty()) return fromSdk
        Trace.i(TAG, "attribution recovered from GCD")
        settled = fromGcd
        return fromGcd
    }

    private val conversionListener = object : AppsFlyerConversionListener {

        override fun onConversionDataSuccess(raw: MutableMap<String, Any?>) {
            Trace.i(TAG, "onConversionDataSuccess")
            bg.launch {
                val status = raw["af_status"]?.toString().orEmpty()
                val resolved = if (status.equals("Organic", ignoreCase = true)) {
                    delay(Env.organicGcdDelayMs)
                    fetchGcd() ?: raw
                } else {
                    raw
                }
                finish(resolved)
            }
        }

        override fun onConversionDataFail(err: String?) {
            Trace.w(TAG, "onConversionDataFail: $err")
            finish(emptyMap())
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data?.forEach { (k, v) -> deepLinkParams[k] = v }
        }

        override fun onAttributionFailure(err: String?) {
            Trace.w(TAG, "onAttributionFailure: $err")
            finish(emptyMap())
        }
    }

    private val deepLinkListener = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            Trace.i(TAG, "deep link status=${result.status}")
            deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { k -> deepLinkParams[k] = click.opt(k) }
        }
        deepLink.complete(Unit)
    }

    private fun finish(data: Map<String, Any?>) {
        settled = data
        if (!attribution.isCompleted) attribution.complete(data)
    }

    /**
     * GCD API call — returns AppsFlyer's own record of the install, or null.
     *
     * The dev key goes in the query string. Sent as an `Authorization: Bearer`
     * header instead, AppsFlyer answers 400 with `"The 'devkey' query parameter
     * is not found"`, and this safety net silently catches nothing. See
     * pitfalls #37.
     */
    private suspend fun fetchGcd(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            val uid = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: return@withContext null
            val base = Env.resolveGcdBase()
            val key = Env.resolveTrackerKey()
            if (base.isBlank() || key.isBlank()) return@withContext null
            val req = Request.Builder()
                .url("$base${Env.bundleId}?devkey=$key&device_id=$uid")
                .addHeader("User-Agent", UserAgent.value)
                .get()
                .build()
            Trace.i(TAG, "GCD GET $base${Env.bundleId}?devkey=***&device_id=$uid")
            gcdHttp.newCall(req).execute().use { resp ->
                // The body carries the reason — a bare status cannot tell a
                // malformed request from an install AppsFlyer has not filed yet.
                val body = resp.body?.string().orEmpty()
                Trace.i(TAG, "GCD HTTP ${resp.code} body=${body.take(2_000)}")
                if (!resp.isSuccessful || body.isBlank()) return@withContext null
                jsonToMap(JSONObject(body))
            }
        } catch (e: Exception) {
            Trace.w(TAG, "GCD fetch failed: ${e.message}")
            null
        }
    }

    fun getAppsFlyerId(): String =
        AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: ""

    /**
     * Attribution + deep-link parameters as a compact JSON object (values
     * stringified). Device fields are NOT added here — [ConfigClient.buildBody]
     * appends af_id/bundle_id/os/store_id/locale/push_token/firebase_project_id
     * before handing the merged payload to the native transport.
     */
    fun composeAttributionJson(attributionData: Map<String, Any?>): String =
        JSONObject().apply {
            attributionData.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
            deepLinkParams.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }
        }.toString()

    /**
     * Builds the POST body for the config endpoint: conversion fields verbatim,
     * then deep-link keys not already present, then device fields, which win.
     */
    fun buildRequestBody(
        attributionData: Map<String, Any?>,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String
    ): JSONObject = JSONObject().apply {
        attributionData.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
        deepLinkParams.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }

        put("af_id", getAppsFlyerId())
        put("bundle_id", Env.bundleId)
        put("os", os)
        put("store_id", Env.bundleId)
        put("locale", locale)
        if (!pushToken.isNullOrBlank()) put("push_token", pushToken)
        if (firebaseProject.isNotBlank()) put("firebase_project_id", firebaseProject)
        Trace.i(TAG, "request body composed (${length()} fields)")
    }

    fun shutdown() {
        bg.cancel()
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { obj.opt(it) }

    private companion object {
        const val TAG = "AttrHub"
        const val RETRACE_WAIT_MS = 8_000L
    }
}
