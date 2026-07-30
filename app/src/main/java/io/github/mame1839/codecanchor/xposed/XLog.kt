package io.github.mame1839.codecanchor.xposed

import android.util.Log
import de.robv.android.xposed.XposedBridge

internal object XLog {
    const val TAG = "CodecAnchor"

    @Volatile
    var verbose = false

    fun i(message: String) {
        Log.i(TAG, message)
        runCatching { XposedBridge.log("$TAG: $message") }
    }

    fun d(message: String) {
        if (verbose) i(message)
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        runCatching {
            XposedBridge.log("$TAG: $message")
            if (t != null) XposedBridge.log(t)
        }
    }
}
