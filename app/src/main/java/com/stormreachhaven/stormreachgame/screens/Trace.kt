package com.stormreachhaven.stormreachgame.screens

import android.util.Log
import com.stormreachhaven.stormreachgame.BuildConfig

/**
 * Debug-only log. [inline] puts the [BuildConfig.DEBUG] check at the call
 * site, so a release build constant-folds the branch and never emits the
 * message string into the dex.
 */
internal object Trace {
    inline fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    inline fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
    }

    inline fun w(tag: String, msg: String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, t)
    }

    inline fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg)
    }

    inline fun e(tag: String, msg: String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, t)
    }
}
