package com.shadetweak.hyperos

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Настройки модуля внутри процесса SystemUI.
 *
 * Читаются из Settings.Global и обновляются через ContentObserver — после
 * изменения в приложении вызывается [onChanged] (на главном потоке).
 */
internal object ShadeConfig {

    @Volatile var enabled = false
        private set
    @Volatile var color = Keys.SYSTEM_COLOR
        private set
    @Volatile var forceSolid = true
        private set
    @Volatile var modal = true
        private set

    @Volatile var resolver: ContentResolver? = null
        private set

    /** Вызывается на главном потоке после каждого изменения настроек. */
    @Volatile var onChanged: (() -> Unit)? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val applyRunnable = Runnable {
        reload()
        HookLog.i("настройки изменены: ${describe()}")
        try {
            onChanged?.invoke()
        } catch (t: Throwable) {
            HookLog.e("ошибка применения настроек", t)
        }
    }

    @Synchronized
    fun init(context: Context?) {
        if (resolver != null || context == null) return
        val cr = (context.applicationContext ?: context).contentResolver ?: return
        resolver = cr
        reload()
        HookLog.i("настройки загружены: ${describe()}")
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                // несколько ключей пишутся подряд — применяем один раз
                mainHandler.removeCallbacks(applyRunnable)
                mainHandler.postDelayed(applyRunnable, 50)
            }
        }
        try {
            for (key in Keys.CONFIG) {
                cr.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }
        } catch (t: Throwable) {
            HookLog.e("не удалось подписаться на Settings.Global", t)
        }
    }

    fun reload() {
        val cr = resolver ?: return
        try {
            val parsed = Keys.parseColor(Settings.Global.getString(cr, Keys.COLOR))
            enabled = Settings.Global.getString(cr, Keys.ENABLED) == "1" && parsed != null
            color = parsed ?: Keys.SYSTEM_COLOR
            forceSolid = Settings.Global.getString(cr, Keys.FORCE_SOLID) != "0"
            modal = Settings.Global.getString(cr, Keys.MODAL) != "0"
        } catch (t: Throwable) {
            HookLog.e("не удалось прочитать Settings.Global", t)
        }
    }

    fun describe(): String =
        "enabled=$enabled color=${Keys.formatColor(color)} forceSolid=$forceSolid modal=$modal"
}
