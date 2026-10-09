package com.stormreachhaven.stormreachgame

import android.util.Log

/**
 * JNI bridge to the native routing gate (libgate.so).
 *
 * The HTTPS POST goes out through a BoringSSL stack whose TLS fingerprint,
 * header order and User-Agent match a real Chrome (`wreq` with
 * `Emulation::Chrome134`), so a Cloudflare-fronted endpoint accepts the
 * request rather than returning a 403 bot challenge. Kotlin never opens
 * the socket itself.
 *
 * Request shaping (URL, method, headers, body) is Kotlin's job and travels
 * in as plain strings; Rust just drives wreq to put the bytes on the wire.
 *
 * Kept in the root package so the JNI symbol names stay stable regardless
 * of any gray sub-package renaming.
 */
internal object NativeGate {

    @Volatile private var available = false

    init {
        // Preload `libc++_shared.so`. `libhaven.so` has a NEEDED entry on it
        // (BoringSSL + wreq's C++ bits are built against the NDK shared
        // libc++), and on most devices Bionic's loader resolves it out of the
        // app's nativeLibraryDir automatically; the explicit load is cheap
        // insurance for devices where it does not. A failure here is not
        // fatal — the second `loadLibrary` call that follows will surface
        // the real reason if anything is actually missing.
        runCatching { System.loadLibrary("c++_shared") }

        // The result of this call matters for every gate call that follows,
        // so log the exact failure cause if it falls over. `UnsatisfiedLink
        // Error` on a missing NEEDED (`libc++_shared.so`, a sibling ABI dir,
        // a stripped symbol) is otherwise invisible because the `runCatching`
        // here swallows it. The log line lets a device-side `adb logcat`
        // tell at a glance whether the gate is live.
        runCatching { System.loadLibrary("haven") }
            .onSuccess { available = true }
            .onFailure { t ->
                available = false
                Log.w(TAG, "libhaven.so load failed: ${t.javaClass.simpleName}: ${t.message}")
            }
    }

    private const val TAG = "NativeGate"

    val isReady: Boolean get() = available

    /**
     * Fire an HTTPS request through the Chrome-emulated transport in
     * `libhaven.so`.
     *
     * @param url            full request URL (scheme + host + path + query).
     * @param method         HTTP method, e.g. `"POST"` or `"GET"`.
     * @param headerName     single extra header name, or empty to skip.
     *                       Used for `"Content-Type"` on POST bodies.
     * @param headerValue    value for [headerName], ignored when the name is
     *                       empty.
     * @param body           request body as a UTF-8 string. Pass empty for
     *                       GET-style calls.
     *
     * Returns `"{status}\n{body}"`. Status 0 means the request never
     * produced a real HTTP reply (DNS, TLS, timeout). Anything else is a
     * real reply from the server. The emulation sets the User-Agent;
     * **do not pass one from Kotlin** — a browser UA on a non-browser
     * handshake is the exact mismatch Cloudflare clusters on.
     */
    external fun route(
        url: String,
        method: String,
        headerName: String,
        headerValue: String,
        body: String,
    ): String

    /**
     * Returns the keyboard-pan JavaScript injection. Owning the script in Rust
     * keeps the sentinel/bridge/frame-walker scaffolding out of the DEX constant
     * pool — only the per-project [sentinel] and [bridge] values reach the APK,
     * embedded in `libhaven.so` next to the template.
     *
     * On an unavailable gate (`libhaven.so` failed to load) callers must fall
     * back to a Kotlin-side source; see [com.stormreachhaven.stormreachgame
     * .connectivity.KeyboardTide.script].
     */
    external fun keyboardScript(
        sentinel: String,
        bridge: String,
        marginCss: Int,
    ): String

    /**
     * XOR-encrypt + HMAC-tag [plaintext] with the vault key that lives in
     * `libgate.so`. [aad] is bound into the tag so a blob written under one
     * key cannot be replayed under another. Returns the base64url sealed
     * blob, or an empty string on any error.
     */
    external fun seal(plaintext: String, aad: String): String

    /**
     * Verify the tag on [sealed] against [aad] and return the decrypted
     * plaintext. Empty string on tag mismatch, base64 error or missing
     * bytes — the caller must treat that as "no valid value stored".
     */
    external fun open(sealed: String, aad: String): String
}
