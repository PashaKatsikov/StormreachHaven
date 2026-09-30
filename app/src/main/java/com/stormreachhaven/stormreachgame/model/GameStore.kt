package com.stormreachhaven.stormreachgame.model

import android.content.Context
import android.content.SharedPreferences
import com.stormreachhaven.stormreachgame.NativeGate

/**
 * Progress storage with an offline anti-cheat wrapper.
 *
 * Every value is stored as a single base64 string built by [NativeGate.seal]
 * — XOR-encrypted with a SHA-256 keystream and HMAC-tagged, keyed with a
 * secret that lives only inside `libgate.so`. The SharedPrefs key is bound
 * into the tag as additional-authenticated-data, so an attacker cannot move
 * a legitimate blob from one preference to another (e.g. copy the sealed
 * "energy" value on top of "stones").
 *
 * On read the tag is verified inside the .so; a mismatch, malformed base64,
 * or a plaintext value dropped in by a hex editor all decode to an empty
 * string on the native side, which this class translates into "no valid
 * value stored" and hands back the caller's fallback. Net effect: a tampered
 * save resets that field to default on the next launch.
 *
 * Fully offline — no network is ever contacted. If `libgate.so` failed to
 * load at all (development / emulator without the ABI), storage silently
 * degrades to plaintext so the game itself never bricks.
 */
class GameStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stormreach_save", Context.MODE_PRIVATE)

    // ── sealed I/O helpers ─────────────────────────────────────────────────

    private fun readSealed(key: String): String? {
        // getString throws ClassCastException if a previous install wrote a
        // non-string primitive under the same key — swallow it and treat the
        // slot as empty; the next write will seal it correctly.
        val raw = runCatching { prefs.getString(key, null) }.getOrNull() ?: return null
        if (!NativeGate.isReady) return raw
        val opened = runCatching { NativeGate.open(raw, key) }.getOrNull()
        return opened?.takeIf { it.isNotEmpty() }
    }

    private fun writeSealed(key: String, value: String) {
        val toStore = if (NativeGate.isReady) {
            runCatching { NativeGate.seal(value, key) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: value
        } else {
            value
        }
        prefs.edit().putString(key, toStore).apply()
    }

    // ── typed accessors (same API as before) ───────────────────────────────

    fun int(key: String, fallback: Int = 0): Int =
        readSealed(key)?.toIntOrNull() ?: fallback

    fun bool(key: String, fallback: Boolean = true): Boolean =
        readSealed(key)?.let { it == "true" } ?: fallback

    fun long(key: String, fallback: Long = 0L): Long =
        readSealed(key)?.toLongOrNull() ?: fallback

    fun float(key: String, fallback: Float = 0f): Float =
        readSealed(key)?.toFloatOrNull() ?: fallback

    fun putInt(key: String, value: Int) {
        writeSealed(key, value.toString())
    }

    fun putBool(key: String, value: Boolean) {
        writeSealed(key, value.toString())
    }

    fun putLong(key: String, value: Long) {
        writeSealed(key, value.toString())
    }

    fun putFloat(key: String, value: Float) {
        writeSealed(key, value.toString())
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun add(key: String, amount: Int) {
        putInt(key, int(key) + amount)
    }

    fun spend(key: String, amount: Int): Boolean {
        val current = int(key)
        if (current < amount) return false
        putInt(key, current - amount)
        return true
    }
}
