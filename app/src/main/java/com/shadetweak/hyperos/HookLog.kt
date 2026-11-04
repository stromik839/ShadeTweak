package com.shadetweak.hyperos

import de.robv.android.xposed.XposedBridge
import java.util.Collections

/** Лог в LSPosed Manager -> Логи (искать "ShadeTweak"). */
internal object HookLog {
    private const val TAG = "ShadeTweak"
    private val once: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun i(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("$TAG: ОШИБКА: $msg" + if (t != null) " -> $t" else "")
    }

    /** Ошибка из часто вызываемого места — пишем в лог только первый раз. */
    fun eOnce(key: String, msg: String, t: Throwable? = null) {
        if (once.add(key)) e(msg, t)
    }
}
