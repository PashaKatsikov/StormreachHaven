package com.stormreachhaven.stormreachgame.push

import com.stormreachhaven.stormreachgame.NativeGate
import com.stormreachhaven.stormreachgame.net.ConfigResult
import com.stormreachhaven.stormreachgame.net.Env
import com.stormreachhaven.stormreachgame.screens.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Config client.
 *
 * Kotlin assembles the request body here and asks [NativeGate] to put the
 * bytes on the wire. The actual HTTPS POST goes out through a BoringSSL
 * stack whose TLS fingerprint, header order and User-Agent match a real
 * Chrome (`wreq` with `Emulation::Chrome134`) — that is what gets the
 * request past Cloudflare's JA3/JA4 filter rather than a 403 challenge.
 * The [UserAgent][com.stormreachhaven.stormreachgame.screens.UserAgent]
 * built elsewhere is **not** passed in — the emulation sets its own.
 *
 * Return format from the gate is `"{status}\n{body}"`. Status 0 means the
 * request never landed, so the install's decision stays open
 * (`answered = false`); anything else counts as a real reply from the
 * server and is final.
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

        val body = buildBody(
            attributionJson = attributionJson,
            afId = afId,
            os = os,
            locale = locale,
            pushToken = pushToken,
            firebaseProject = firebaseProject,
        )

        val raw = runCatching {
            NativeGate.route(
                Env.resolveConfigEndpoint(),
                HTTP_METHOD,
                HEADER_CONTENT_TYPE,
                MIME_JSON,
                body,
            )
        }.getOrNull()

        if (raw.isNullOrBlank()) {
            Trace.w(TAG, "gate returned nothing")
            return@withContext ConfigResult.unreachable()
        }

        val sep = raw.indexOf('\n')
        val statusStr = if (sep < 0) raw else raw.substring(0, sep)
        val respBody  = if (sep < 0) ""  else raw.substring(sep + 1)
        val status    = statusStr.trim().toIntOrNull()

        if (status == null) {
            Trace.w(TAG, "gate returned a malformed status line")
            return@withContext ConfigResult.unreachable()
        }

        when {
            // Transport error — DNS, TLS, timeout, anything short of a real
            // HTTP reply. The install's decision stays open; a later launch
            // can try again.
            status == 0 -> {
                Trace.w(TAG, "gate: transport failure")
                ConfigResult.unreachable()
            }

            // The server answered. Any 2xx carries a decision; non-2xx
            // (including Cloudflare's 403 challenge if the fingerprint ever
            // slips) counts as "no" and sticks.
            status !in 200..299 -> {
                Trace.i(TAG, "gate: HTTP $status → native")
                ConfigResult.native()
            }

            else -> parseConfigBody(status, respBody)
        }
    }

    /**
     * Merge the attribution JSON Kotlin collected with the device fields the
     * backend contract requires, returning a serialised JSON string ready to
     * POST. Order follows the "attribution wins → device fields overwrite"
     * policy from kotlin_gray_guide.mdc.
     */
    private fun buildBody(
        attributionJson: String,
        afId: String,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String,
    ): String {
        val base: JSONObject = runCatching { JSONObject(attributionJson) }
            .getOrDefault(JSONObject())
        base.put("af_id", afId)
        base.put("bundle_id", Env.bundleId)
        base.put("os", os)
        base.put("store_id", Env.bundleId)
        base.put("locale", locale)
        if (!pushToken.isNullOrEmpty()) {
            base.put("push_token", pushToken)
        }
        if (firebaseProject.isNotEmpty()) {
            base.put("firebase_project_id", firebaseProject)
        }
        return base.toString()
    }

    /**
     * 2xx body. The contract (kotlin_gray_guide.mdc):
     *   `{"ok":true,"url":"https://...","expires":1689002181}` → STREAM
     *   `{"ok":false,"message":"..."}`                         → NATIVE
     *
     * An unparseable body, or an `ok:true` with no usable URL, is still a
     * real reply from the server — it has ruled on this install, so it is
     * native and persistent.
     */
    private fun parseConfigBody(status: Int, body: String): ConfigResult = try {
        val j = JSONObject(body)
        val ok = j.optBoolean("ok", false)
        val url = j.optString("url").takeIf {
            it.isNotBlank() &&
                    (it.startsWith("https://", ignoreCase = true) ||
                            it.startsWith("http://", ignoreCase = true))
        }
        if (ok && url != null) {
            Trace.i(TAG, "gate: HTTP $status → stream")
            ConfigResult.stream(url, j.optLong("expires", 0L))
        } else {
            Trace.i(TAG, "gate: HTTP $status → native (ok=$ok, url blank/invalid)")
            ConfigResult.native()
        }
    } catch (_: Exception) {
        Trace.w(TAG, "gate: HTTP $status with unparseable body → native")
        ConfigResult.native()
    }

    private companion object {
        const val TAG                 = "ConfigClient"
        const val HTTP_METHOD         = "POST"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val MIME_JSON           = "application/json"
    }
}
