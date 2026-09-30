package com.stormreachhaven.stormreachgame

/**
 * JNI bridge to the native routing gate (libgate.so).
 *
 * The endpoint URL, the veil secret + envelope codec and the stream/native
 * decision live in Rust and never appear as plaintext in the DEX. Kotlin only
 * collects attribution and performs the HTTPS POST with the endpoint + body
 * this returns, then hands the response back to [decide].
 *
 * Kept in the root package so the JNI symbol names stay stable regardless of
 * any gray sub-package renaming.
 */
internal object NativeGate {

    @Volatile private var available = false

    init {
        available = runCatching { System.loadLibrary("haven") }.isSuccess
    }

    val isReady: Boolean get() = available

    /**
     * Full gate: packs the veil envelope, POSTs it to the relay over HTTPS and
     * returns the verdict `{"mode":"stream|native|unreachable","url":..,"expires":n}`.
     * Kotlin never sees the endpoint and makes no network call of its own.
     */
    external fun route(
        attribution: String,
        afId: String,
        bundleId: String,
        os: String,
        locale: String,
        pushToken: String,
        firebaseProject: String,
        ua: String,
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
