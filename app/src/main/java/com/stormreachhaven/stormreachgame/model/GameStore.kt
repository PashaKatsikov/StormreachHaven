package com.stormreachhaven.stormreachgame.model

import android.content.Context
import android.content.SharedPreferences

class GameStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stormreach_save", Context.MODE_PRIVATE)

    fun int(key: String, fallback: Int = 0): Int = prefs.getInt(key, fallback)

    fun bool(key: String, fallback: Boolean = true): Boolean = prefs.getBoolean(key, fallback)

    fun long(key: String, fallback: Long = 0L): Long = prefs.getLong(key, fallback)

    fun float(key: String, fallback: Float = 0f): Float = prefs.getFloat(key, fallback)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun putBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
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
