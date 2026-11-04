package com.shadetweak.hyperos

import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Хуки SystemUI HyperOS 2 (разобрано по MiuiSystemUI 20240808.0, Android 15).
 *
 * Как HyperOS рисует фон шторки, когда блюр выключен:
 *  - ShadeSolidBackgroundController хранит цвет в полях solidAlpha / solidRGB
 *    (берутся из color/shade_solid_background_color = #FF6E6E6E);
 *  - updateWindow() красит view shade_background в окне шторки
 *    (уведомления и Центр управления поверх рабочего стола);
 *  - updateCtrl$1() красит такой же view внутри Центра управления
 *    (используется, когда шторку открывают на экране блокировки);
 *  - итоговая альфа = solidAlpha * степень раскрытия шторки (0..1),
 *    поэтому при solidAlpha = 0 фон полностью прозрачный;
 *  - ShadeBlurController.screenshotBlur = true (слабые устройства или
 *    debug.sysui.screenshot_blur) включает "скриншот-блюр": фоном становится
 *    размытый скриншот или серый ColorDrawable прямо из ресурсов, а
 *    solidAlpha/solidRGB не используются вообще. Поэтому старая версия
 *    иногда "не меняла цвет".
 *
 * Что делает модуль:
 *  1. держит свои значения в solidAlpha/solidRGB: после ShadeBlurController.start(),
 *     после access$updateResources (смена темы) и перед каждым
 *     updateWindow/updateCtrl. Даже если ART заинлайнил какой-то вызов, в полях
 *     всё равно лежит наш цвет;
 *  2. если включено, выключает screenshotBlur до ShadeBlurController.start();
 *  3. то же для BlurUtilsExt.solidColor* (фон модального окна уведомления);
 *  4. по изменению Settings.Global сразу всё перерисовывает, без перезапуска SystemUI.
 */
internal object ShadeHooks {
    const val SOLID_CLASS = "com.miui.systemui.shade.blur.ShadeSolidBackgroundController"
    private const val BLUR_CLASS = "com.miui.systemui.shade.blur.ShadeBlurController"
    private const val BLUR_UTILS_EXT_CLASS = "com.android.systemui.statusbar.policy.BlurUtilsExt"

    @Volatile private var solidRef: WeakReference<Any>? = null
    @Volatile private var blurRef: WeakReference<Any>? = null
    @Volatile private var blurUtilsExtRef: WeakReference<Any>? = null

    private var solidAlphaField: Field? = null
    private var solidRgbField: Field? = null
    private var solidUpdateResources: Method? = null
    private var solidUpdateMethods: List<Method> = emptyList()

    private var modalAlphaField: Field? = null
    private var modalRgbField: Field? = null
    private var modalUpdateResources: Method? = null

    /** Каким screenshotBlur был у системы до нас (null — ещё не знаем). */
    @Volatile private var systemScreenshotBlur: Boolean? = null
    @Volatile private var forcedSolid = false
    @Volatile private var hookCount = 0
    @Volatile private var lastStatus: String? = null

    fun install(cl: ClassLoader, solidCls: Class<*>) {
        ShadeConfig.onChanged = { reapplyAll() }

        hookSolid(solidCls)

        val blurCls = XposedHelpers.findClassIfExists(BLUR_CLASS, cl)
        if (blurCls != null) hookBlur(blurCls) else HookLog.e("$BLUR_CLASS не найден")

        val extCls = XposedHelpers.findClassIfExists(BLUR_UTILS_EXT_CLASS, cl)
        if (extCls != null) {
            hookBlurUtilsExt(extCls)
        } else {
            HookLog.i("$BLUR_UTILS_EXT_CLASS не найден — модальное окно не трогаю")
        }

        deoptimizeCallers(cl)
        HookLog.i("установлено хуков: $hookCount")
    }

    // ---------------------------------------------------------------- solid-фон

    private fun hookSolid(cls: Class<*>) {
        solidAlphaField = findField(cls, "solidAlpha")
        solidRgbField = findField(cls, "solidRGB")
        if (solidAlphaField == null || solidRgbField == null) {
            HookLog.e("поля solidAlpha/solidRGB не найдены — эта сборка SystemUI отличается")
        }

        hookAllConstructors(cls, after { p ->
            val obj = p.thisObject ?: return@after
            solidRef = WeakReference(obj)
            ShadeConfig.init(readField(obj, "context") as? Context)
        })

        // updateWindow() — фон окна шторки, updateCtrl$1() — фон Центра управления
        val updates = cls.declaredMethods.filter { m ->
            m.parameterTypes.isEmpty() && !Modifier.isStatic(m.modifiers) &&
                (m.name == "updateWindow" || m.name.startsWith("updateCtrl"))
        }
        solidUpdateMethods = updates
        updates.forEach { it.isAccessible = true }
        if (updates.isEmpty()) HookLog.e("updateWindow/updateCtrl не найдены")
        val beforeUpdate = before { p ->
            val obj = p.thisObject ?: return@before
            if (solidRef?.get() !== obj) solidRef = WeakReference(obj)
            applySolid(obj)
        }
        for (m in updates) hook(m, beforeUpdate)

        // static access$updateResources(controller): перечитывает цвет из ресурсов
        // при смене темы / тёмного режима — после него снова ставим свой
        val updRes = cls.declaredMethods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) && m.name.contains("updateResources") &&
                m.parameterTypes.size == 1 && m.parameterTypes[0] == cls
        }
        solidUpdateResources = updRes
        if (updRes != null) {
            updRes.isAccessible = true
            hook(updRes, after { p -> p.args.getOrNull(0)?.let { applySolid(it) } })
        } else {
            HookLog.e("access\$updateResources не найден")
        }
    }

    private fun applySolid(obj: Any) {
        if (!ShadeConfig.enabled) return
        val c = ShadeConfig.color
        try {
            solidAlphaField?.setInt(obj, (c ushr 24) and 0xFF)
            solidRgbField?.setInt(obj, c and 0x00FFFFFF)
        } catch (t: Throwable) {
            HookLog.eOnce("solid", "не удалось записать solidAlpha/solidRGB", t)
        }
    }

    // ------------------------------------------------------------ режим блюра

    private fun hookBlur(cls: Class<*>) {
        hookAllConstructors(cls, after { p ->
            val obj = p.thisObject ?: return@after
            blurRef = WeakReference(obj)
            ShadeConfig.init(p.args.firstOrNull { it is Context } as? Context)
            if (solidRef?.get() == null) {
                readField(obj, "solidBackgroundController")?.let { solidRef = WeakReference(it) }
            }
            val screenshot = readField(obj, "screenshotBlur") as? Boolean
            systemScreenshotBlur = screenshot
            HookLog.i("системный screenshotBlur=$screenshot")
            // start() ещё не вызывался, значит скриншот-блюр даже не запустится
            if (screenshot == true && ShadeConfig.enabled && ShadeConfig.forceSolid &&
                writeField(obj, "screenshotBlur", false)
            ) {
                forcedSolid = true
                HookLog.i("скриншот-блюр выключен — фон шторки будет сплошным цветом")
            }
        })

        val start = cls.declaredMethods.firstOrNull { it.name == "start" && it.parameterTypes.isEmpty() }
        if (start != null) {
            // в start() поля solidAlpha/solidRGB заполняются из ресурсов — перезаписываем
            hook(start, after { _ ->
                solidRef?.get()?.let { applySolid(it) }
                publishStatus()
            })
        } else {
            HookLog.e("ShadeBlurController.start() не найден")
        }
    }

    // ------------------------------------------- модальное окно уведомления

    private fun hookBlurUtilsExt(cls: Class<*>) {
        modalAlphaField = findField(cls, "solidColorAlpha")
        modalRgbField = findField(cls, "solidColorRGB")
        val m = cls.declaredMethods.firstOrNull {
            it.name.startsWith("updateResources") && it.parameterTypes.isEmpty() &&
                !Modifier.isStatic(it.modifiers)
        }
        if (m == null || modalAlphaField == null || modalRgbField == null) {
            HookLog.i("BlurUtilsExt другой структуры — модальное окно не трогаю")
            return
        }
        m.isAccessible = true
        modalUpdateResources = m
        hook(m, after { p ->
            val obj = p.thisObject ?: return@after
            blurUtilsExtRef = WeakReference(obj)
            ShadeConfig.init(readField(obj, "context") as? Context)
            applyModal(obj)
        })
    }

    private fun applyModal(obj: Any) {
        if (!ShadeConfig.enabled || !ShadeConfig.modal) return
        val c = ShadeConfig.color
        try {
            modalAlphaField?.setInt(obj, (c ushr 24) and 0xFF)
            modalRgbField?.setInt(obj, c and 0x00FFFFFF)
        } catch (t: Throwable) {
            HookLog.eOnce("modal", "не удалось записать solidColorAlpha/solidColorRGB", t)
        }
    }

    // ---------------------------------------------------- живое применение

    /** Главный поток: настройки в Settings.Global изменились. */
    private fun reapplyAll() {
        solidRef?.get()?.let { obj ->
            try {
                // до ShadeBlurController.start() потоков window/ctrl ещё нет
                if (readField(obj, "window") != null) {
                    val m = solidUpdateResources
                    if (m != null) {
                        // перечитает системный цвет, наш хук подставит свой и перерисует фон;
                        // если модуль выключили — вернётся системный цвет
                        m.invoke(null, obj)
                    } else {
                        applySolid(obj)
                        solidUpdateMethods.forEach { it.invoke(obj) }
                    }
                }
            } catch (t: Throwable) {
                HookLog.e("не удалось перерисовать фон шторки", t)
            }
        }
        blurUtilsExtRef?.get()?.let { obj ->
            try {
                modalUpdateResources?.invoke(obj)
            } catch (t: Throwable) {
                HookLog.e("не удалось обновить BlurUtilsExt", t)
            }
        }
        publishStatus()
    }

    /** Пишет в Settings.Global, что видит хук — приложение показывает это как статус. */
    private fun publishStatus() {
        val cr = ShadeConfig.resolver ?: return
        val blur = blurRef?.get()
        val screenshot = blur?.let { readField(it, "screenshotBlur") as? Boolean }
        val miBlur = blur?.let { flowBool(readField(it, "miBlurOpen")) }
        val winBlur = solidRef?.get()
            ?.let { readField(it, "windowBlurUtils") }
            ?.let { readField(it, "windowBlurAvailable") as? Boolean }
        val needRestart = ShadeConfig.enabled && ShadeConfig.forceSolid && screenshot == true
        val status = listOf(
            "v=${BuildConfig.VERSION_NAME}",
            "hooks=$hookCount",
            "enabled=${flag(ShadeConfig.enabled)}",
            "screenshot=${flag(screenshot)}",
            "systemScreenshot=${flag(systemScreenshotBlur)}",
            "forced=${flag(forcedSolid)}",
            "miblur=${flag(miBlur)}",
            "winblur=${flag(winBlur)}",
            "restart=${flag(needRestart)}",
            "time=${System.currentTimeMillis()}",
        ).joinToString(";")
        val key = status.substringBefore(";time=")
        if (key == lastStatus) return
        lastStatus = key
        HookLog.i("статус: $key")
        Thread {
            try {
                Settings.Global.putString(cr, Keys.STATUS, status)
            } catch (t: Throwable) {
                HookLog.eOnce("status", "не удалось записать статус", t)
            }
        }.start()
    }

    // ------------------------------------------------------------ служебное

    /**
     * LSPosed умеет деоптимизировать методы. Деоптимизируем тех, кто вызывает
     * updateWindow/updateCtrl/access$updateResources, чтобы ART не заинлайнил
     * эти вызовы мимо наших хуков.
     */
    private fun deoptimizeCallers(cl: ClassLoader) {
        val deopt = try {
            XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", Member::class.java)
        } catch (t: Throwable) {
            null
        }
        if (deopt == null) {
            HookLog.i("deoptimizeMethod недоступен — пропускаю")
            return
        }
        var count = 0
        val callers = listOf(
            "$SOLID_CLASS\$start\$3\$1\$1",     // коллектор потоков window/ctrl
            "$SOLID_CLASS\$configListener\$1",  // смена темы / тёмного режима
        )
        for (name in callers) {
            val c = XposedHelpers.findClassIfExists(name, cl) ?: continue
            for (m in c.declaredMethods) {
                if (Modifier.isAbstract(m.modifiers)) continue
                try {
                    deopt.invoke(null, m)
                    count++
                } catch (t: Throwable) {
                    // не критично
                }
            }
        }
        HookLog.i("деоптимизировано методов: $count")
    }

    private fun hook(m: Member, cb: XC_MethodHook) {
        try {
            XposedBridge.hookMethod(m, cb)
            hookCount++
            HookLog.i("хук: ${m.declaringClass.simpleName}.${m.name}")
        } catch (t: Throwable) {
            HookLog.e("не удалось поставить хук на ${m.name}", t)
        }
    }

    private fun hookAllConstructors(cls: Class<*>, cb: XC_MethodHook) {
        try {
            hookCount += XposedBridge.hookAllConstructors(cls, cb).size
        } catch (t: Throwable) {
            HookLog.e("не удалось поставить хук на конструктор ${cls.simpleName}", t)
        }
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun readField(obj: Any, name: String): Any? = try {
        findField(obj.javaClass, name)?.get(obj)
    } catch (t: Throwable) {
        null
    }

    private fun writeField(obj: Any, name: String, value: Any?): Boolean = try {
        val f = findField(obj.javaClass, name)
        if (f == null) {
            false
        } else {
            f.set(obj, value)
            true
        }
    } catch (t: Throwable) {
        HookLog.e("не удалось записать поле $name", t)
        false
    }

    private fun flowBool(flow: Any?): Boolean? = try {
        flow?.let { XposedHelpers.callMethod(it, "getValue") as? Boolean }
    } catch (t: Throwable) {
        null
    }

    private fun flag(v: Boolean?): String = when (v) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    private inline fun before(crossinline block: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    block(param)
                } catch (t: Throwable) {
                    HookLog.eOnce("before:${param.method}", "ошибка в хуке ${param.method}", t)
                }
            }
        }

    private inline fun after(crossinline block: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    block(param)
                } catch (t: Throwable) {
                    HookLog.eOnce("after:${param.method}", "ошибка в хуке ${param.method}", t)
                }
            }
        }
}
