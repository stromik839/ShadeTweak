package com.shadetweak.hyperos

import android.graphics.Color
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Знайдено статичним аналізом systemui.zip (HyperOS 2.0.210.0, Redmi Note 12S),
 * НЕ перевірено на реальному пристрої розробником. Якщо після встановлення
 * нічого не змінюється - дивись логи LSPosed Manager -> Logs, шукай "ShadeTweak".
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
        private const val DEFAULT_ARGB = "40FFFFFF" // білий, прозорість ~25% за замовчуванням
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("$TAG: handleLoadPackage увійшов у $TARGET_PACKAGE, модуль завантажено")

        val prefs = XSharedPreferences(OWN_PACKAGE, PREFS_NAME)
        val classLoader = lpparam.classLoader

        fun targetOf(param: XC_MethodHook.MethodHookParam): Any? =
            param.thisObject ?: param.args.getOrNull(0)

        fun applyOverride(instance: Any?, caller: String) {
            if (instance == null) {
                XposedBridge.log("$TAG[$caller]: instance == null, пропускаю")
                return
            }
            prefs.reload()
            val enabled = prefs.getBoolean("enabled", true)
            if (!enabled) {
                XposedBridge.log("$TAG[$caller]: enabled=false, лишаю оригінал")
                return
            }
            val argbHex = prefs.getString("argb", DEFAULT_ARGB) ?: DEFAULT_ARGB
            val color = try {
                Color.parseColor("#$argbHex")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG[$caller]: не зміг розпарсити '$argbHex', fallback на дефолт: $t")
                Color.parseColor("#$DEFAULT_ARGB")
            }
            val alpha = Color.alpha(color)
            val rgb = color and 0x00FFFFFF
            XposedBridge.log(
                "$TAG[$caller]: прочитано argb=$argbHex -> alpha=$alpha rgb=${Integer.toHexString(rgb)}"
            )
            try {
                XposedHelpers.setIntField(instance, "solidAlpha", alpha)
                XposedHelpers.setIntField(instance, "solidRGB", rgb)
                // читаємо назад одразу, щоб підтвердити що значення реально збереглись
                val readbackAlpha = XposedHelpers.getIntField(instance, "solidAlpha")
                val readbackRgb = XposedHelpers.getIntField(instance, "solidRGB")
                XposedBridge.log(
                    "$TAG[$caller]: після setIntField readback alpha=$readbackAlpha " +
                        "rgb=${Integer.toHexString(readbackRgb)}"
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG[$caller]: setIntField/getIntField не спрацював: $t")
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

        // Hook 1: спрацьовує щоразу, коли вікно шторки оновлюється й читає
        // поточні solidAlpha/solidRGB - тут ми примусово підставляємо свої
        // значення ПЕРЕД тим, як оригінальний код їх використає.
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, "updateWindow", hook)
            XposedBridge.log("$TAG: updateWindow заховано успішно")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: updateWindow hook FAILED: $t")
        }

        // Hook 2: спрацьовує, коли система перераховує кольори з ресурсів
        // (наприклад, зміна теми/дня-ночі) - підставляємо свої значення ПІСЛЯ
        // оригінального розрахунку, щоб не бути перезаписаними.
        val hook2 = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateResources-before")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                applyOverride(targetOf(param), "updateResources-after")
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS, classLoader,
                "access\$updateResources", TARGET_CLASS,
                hook2
            )
            XposedBridge.log("$TAG: access\$updateResources заховано успішно")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: access\$updateResources hook FAILED: $t")
        }

        // === ФАЗА 2 (експериментально, вимкнено за замовчуванням) ===
        // Окремий шар: стандартний AOSP ScrimController - затемнення позаду
        // шторки, яке раніше було непомітне на тлі суцільного сірого. Цей
        // клас використовується ТАКОЖ для екрана блокування/AOD/PIN, тому
        // хук звужено тільки на стан UNLOCKED (шторка над розблокованим
        // домашнім екраном) - інші стани не чіпаються.
        try {
            XposedHelpers.findAndHookMethod(
                SCRIM_CONTROLLER_CLASS, classLoader,
                "setScrimAlpha",
                "com.android.systemui.scrim.ScrimView",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        prefs.reload()
                        val scrimEnabled = prefs.getBoolean("scrim_enabled", false)
                        if (!scrimEnabled) {
                            XposedBridge.log("$TAG[scrim]: scrim_enabled=false, пропускаю")
                            return
                        }
                        try {
                            val state = XposedHelpers.getObjectField(param.thisObject, "mState")
                            val stateName = XposedHelpers.callMethod(state, "name") as? String
                            val originalAlpha = param.args[1]
                            XposedBridge.log(
                                "$TAG[scrim]: setScrimAlpha викликано, state=$stateName " +
                                    "originalAlpha=$originalAlpha"
                            )
                            if (stateName != "UNLOCKED") {
                                XposedBridge.log("$TAG[scrim]: стан не UNLOCKED, лишаю оригінал")
                                return
                            }
                            val target = prefs.getFloat("scrim_alpha", 0.0f)
                            param.args[1] = target
                            XposedBridge.log("$TAG[scrim]: підмінено на $target")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG[scrim]: помилка: $t")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: setScrimAlpha заховано успішно")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: setScrimAlpha hook FAILED: $t")
        }
    }
}
