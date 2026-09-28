package com.stormreachhaven.stormreachgame.push

import com.stormreachhaven.stormreachgame.net.Env
import com.stormreachhaven.stormreachgame.net.ConfigResult
import com.stormreachhaven.stormreachgame.screens.Trace
import com.stormreachhaven.stormreachgame.screens.UrlGuard
import com.stormreachhaven.stormreachgame.screens.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Config endpoint client. One responsibility, one method: POST the attribution
 * body and return the parsed answer.
 *
 * The URL out of a successful response is checked against [UrlGuard] before it
 * is handed back. A destination outside the allowlist is treated the same as
 * `ok:false` — the app opens the native part, and the mode is not persisted
 * (the endpoint did answer, but its answer was rejected by our own gate, so
 * the "did the server rule on this install" question is still open next launch).
 */
class ConfigClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Env.configTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(Env.configTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchChannel(body: JSONObject): ConfigResult = withContext(Dispatchers.IO) {
        val endpoint = Env.resolveConfigEndpoint()
        if (endpoint.isBlank()) {
            Trace.w(TAG, "endpoint is blank — nobody to ask")
            return@withContext ConfigResult.unreachable()
        }
        Trace.i(TAG, "POST config endpoint")
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", UserAgent.value)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw = resp.body?.string().orEmpty()
                Trace.i(TAG, "HTTP $code (${raw.length} chars)")

                if (code == 404) return@withContext ConfigResult.native()
                if (code !in 200..299) return@withContext ConfigResult.native()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Trace.w(TAG, "request never landed: ${e.message}")
            ConfigResult.unreachable()
        }
    }

    private fun parseResponse(raw: String): ConfigResult {
        if (raw.isBlank()) return ConfigResult.native()
        return try {
            val j = JSONObject(raw)
            val ok = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                if (!UrlGuard.accepts(url)) {
                    Trace.w(TAG, "endpoint URL rejected by allowlist")
                    return ConfigResult.native()
                }
                ConfigResult.stream(url, exp)
            } else {
                ConfigResult.native()
            }
        } catch (e: Exception) {
            Trace.w(TAG, "JSON parse error: ${e.message}")
            ConfigResult.native()
        }
    }

    private companion object { const val TAG = "ConfigClient" }
}
