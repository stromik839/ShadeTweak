package com.shadetweak.hyperos

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Точка входа LSPosed (assets/xposed_init).
 *
 * LSPosed может вызвать handleLoadPackage для com.android.systemui несколько раз
 * (например, ещё раз для classloader'а плагина Центра управления). Хуки ставим
 * один раз — в тот classloader, где реально есть нужный класс. Старая версия
 * для этого запускала потоки с повторами и ловила ClassNotFoundException.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI || lpparam.processName != SYSTEMUI) return
        val cl = lpparam.classLoader ?: return
        val solid = XposedHelpers.findClassIfExists(ShadeHooks.SOLID_CLASS, cl)
        if (solid == null) {
            HookLog.i("в этом classloader нет ${ShadeHooks.SOLID_CLASS} — пропускаю")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        HookLog.i("v${BuildConfig.VERSION_NAME}: SystemUI найден, ставлю хуки")
        try {
            ShadeHooks.install(cl, solid)
        } catch (t: Throwable) {
            HookLog.e("установка хуков не удалась", t)
        }
    }

    companion object {
        private const val SYSTEMUI = "com.android.systemui"
        private val installed = AtomicBoolean(false)
    }
}
