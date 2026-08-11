package com.shadetweak.hyperos

import android.graphics.Color
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Знайдено статичним аналізом systemui.zip (HyperOS 2.0.210.0, Redmi Note 12S).
 *
 * З логів (2026-08-10) підтверджено: handleLoadPackage для com.android.systemui
 * викликається LSPosed двічі - раз на головному потоці при старті процесу
 * (клас знаходиться одразу), і вдруге секунд через 4-5 на іншому потоці
 * (ClassNotFoundException) - ймовірно, окремий classloader Центру керування
 * HyperOS, який у момент виклику ще не має класу в собі. Тому кожен хук
 * реєструється з повтором: якщо клас не знайдено одразу, пробуємо ще раз
 * із затримкою, поки classloader не довантажиться.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "ShadeTweak"
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val TARGET_CLASS =
            "com.miui.systemui.shade.blur.ShadeSolidBackgroundController"
        private const val SCRIM_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.phone.ScrimController"
        private const val OWN_PACKAGE = "com.shadetweak.hyperos"
        private const val PREFS_NAME = "shade_tweak_prefs"
        private const val DEFAULT_ARGB = "40FFFFFF"
        private const val RETRY_ATTEMPTS = 8
        private const val RETRY_DELAY_MS = 1000L
    }

    // Пробує зареєструвати хук; якщо клас/метод ще не видно в цьому
    // classloader - повторює з затримкою замість того, щоб здатися одразу.
    private fun hookWithRetry(
        label: String,
        register: () -> Unit
    ) {
        Thread {
            for (attempt in 1..RETRY_ATTEMPTS) {
                try {
                    register()
                    XposedBridge.log("$TAG: $label заховано успішно (спроба $attempt)")
                    return@Thread
                } catch (t: Throwable) {
                    if (attempt == RETRY_ATTEMPTS) {
                        XposedBridge.log("$TAG: $label hook FAILED після $RETRY_ATTEMPTS спроб: $t")
                    } else {
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }
            }
        }.start()
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("$TAG: handleLoadPackage увійшов у $TARGET_PACKAGE, модуль завантажено")

        val prefs = XSharedPreferences(OWN_PACKAGE, PREFS_NAME)
        val classLoader = lpparam.classLoader

        fun targetOf(param: XC_MethodHook.MethodHookParam): Any? =
            param.thisObject ?: param.args.getOrNull(0)

        fun applyOverride(instance: Any?, caller: String) {
            if (instance == null) return
            prefs.reload()
            if (!prefs.getBoolean("enabled", true)) return
            val argbHex = prefs.getString("argb", DEFAULT_ARGB) ?: DEFAULT_ARGB
            val color = try {
                Color.parseColor("#$argbHex")
            } catch (t: Throwable) {
                Color.parseColor("#$DEFAULT_ARGB")
            }
            val alpha = Color.alpha(color)
            val rgb = color and 0x00FFFFFF
            try {
                XposedHelpers.setIntField(instance, "solidAlpha", alpha)
                XposedHelpers.setIntField(instance, "solidRGB", rgb)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG[$caller]: setIntField не спрацював: $t")
            }
        }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateWindow-before")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateWindow-after")
            }
        }
        hookWithRetry("updateWindow") {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, "updateWindow", hook)
        }

        val hook2 = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateResources-before")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateResources-after")
            }
        }
        hookWithRetry("access\$updateResources") {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS, classLoader,
                "access\$updateResources", TARGET_CLASS,
                hook2
            )
        }

        // === ФАЗА 2 (експериментально, вимкнено за замовчуванням) ===
        hookWithRetry("setScrimAlpha") {
            XposedHelpers.findAndHookMethod(
                SCRIM_CONTROLLER_CLASS, classLoader,
                "setScrimAlpha",
                "com.android.systemui.scrim.ScrimView",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        if (!prefs.getBoolean("scrim_enabled", false)) return
                        try {
                            val state = XposedHelpers.getObjectField(param.thisObject, "mState")
                            val stateName = XposedHelpers.callMethod(state, "name") as? String
                            if (stateName != "UNLOCKED") return
                            val target = prefs.getFloat("scrim_alpha", 0.0f)
                            param.args[1] = target
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG[scrim]: помилка: $t")
                        }
                    }
                }
            )
        }
    }
}
