package com.shadetweak.hyperos

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.DataOutputStream
import java.io.File

class SettingsActivity : Activity() {

    private val prefsName = "shade_tweak_prefs"

    // Проста темна/світла палітра - без AppCompat/Material, щоб не тягнути
    // зайві залежності в збірку. Слідує системному темному режиму телефону.
    private class Palette(dark: Boolean) {
        val background = if (dark) Color.parseColor("#121212") else Color.WHITE
        val text = if (dark) Color.parseColor("#E1E1E1") else Color.parseColor("#1A1A1A")
        val secondaryText = if (dark) Color.parseColor("#AAAAAA") else Color.parseColor("#555555")
        val fieldBackground = if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#F0F0F0")
        val buttonBackground = if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#E0E0E0")
        val hint = if (dark) Color.parseColor("#777777") else Color.parseColor("#999999")
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    // Виконує "killall com.android.systemui" через root напряму з застосунку,
    // без Termux. Перший запуск -> KernelSU спитає дозвіл root для цього apk.
    private fun restartSystemUiAsRoot(onDone: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var msg = ""
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("killall com.android.systemui\n")
                os.writeBytes("exit\n")
                os.flush()
                os.close()
                val code = process.waitFor()
                ok = code == 0
                msg = if (ok) "SystemUI перезапущено" else "su завершився з кодом $code"
            } catch (t: Throwable) {
                msg = "root недоступний: ${t.message}"
            }
            runOnUiThread { onDone(ok, msg) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val dark = isDarkMode()
        val pal = Palette(dark)

        window.statusBarColor = pal.background
        if (!dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(pal.background)
        }

        val header = TextView(this).apply {
            text = "Shade Tweak"
            textSize = 22f
            setTextColor(pal.text)
            setPadding(0, 0, 0, pad)
        }

        val info = TextView(this).apply {
            text = "Колір фону шторки (без блюру), формат AARRGGBB.\n\n" +
                "AA — прозорість (00 = невидимо, FF = суцільно непрозоро)\n" +
                "RRGGBB — сам колір\n\n" +
                "Приклади:\n" +
                "40FFFFFF — білий, прозорість ~25%\n" +
                "992B2B2B — темно-сірий, прозорість ~60%\n" +
                "FFFF0000 — суцільно червоний\n" +
                "00FFFFFF — повністю прозоро"
            textSize = 14f
            setTextColor(pal.text)
            setPadding(0, 0, 0, pad)
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("argb", "40FFFFFF"))
            hint = "40FFFFFF"
            setTextColor(pal.text)
            setHintTextColor(pal.hint)
            setBackgroundColor(pal.fieldBackground)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }

        val preview = TextView(this).apply {
            text = "Попередній перегляд"
            gravity = Gravity.CENTER
            setTextColor(pal.text)
            setPadding(0, pad, 0, pad)
            height = (80 * density).toInt()
        }

        fun refreshPreview() {
            try {
                preview.setBackgroundColor(Color.parseColor("#" + input.text.toString().trim()))
            } catch (t: Throwable) {
                preview.setBackgroundColor(Color.DKGRAY)
            }
        }
        refreshPreview()

        fun makeWorldReadable() {
            try {
                val dir = File(applicationInfo.dataDir, "shared_prefs")
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
                val f = File(dir, "$prefsName.xml")
                if (f.exists()) f.setReadable(true, false)
            } catch (t: Throwable) {
                // ігноруємо - на деяких пристроях це не критично
            }
        }

        val statusText = TextView(this).apply {
            setTextColor(pal.secondaryText)
            setPadding(0, pad, 0, 0)
        }

        val saveBtn = Button(this).apply {
            text = "Зберегти й застосувати"
            setTextColor(pal.text)
            setBackgroundColor(pal.buttonBackground)
        }
        saveBtn.setOnClickListener {
            val hex = input.text.toString().trim().uppercase().removePrefix("#")
            try {
                Color.parseColor("#$hex")
                prefs.edit().putString("argb", hex).putBoolean("enabled", true).commit()
                makeWorldReadable()
                refreshPreview()
                statusText.text = "Збережено ($hex). Перезапускаю SystemUI..."
                restartSystemUiAsRoot { ok, msg ->
                    statusText.text = "Збережено ($hex). $msg" +
                        if (!ok) "\nВідкрий шторку вручну або виконай у Termux: " +
                            "su -c \"killall com.android.systemui\"" else ""
                }
            } catch (t: Throwable) {
                input.error = "Невірний формат, приклад: 40FFFFFF"
                statusText.text = "Помилка формату кольору"
            }
        }

        val toggleBtn = Button(this).apply {
            text = if (prefs.getBoolean("enabled", true))
                "Вимкнути заміну (лишити стандартний сірий)" else "Увімкнути заміну"
            setTextColor(pal.text)
            setBackgroundColor(pal.buttonBackground)
        }
        toggleBtn.setOnClickListener {
            val cur = prefs.getBoolean("enabled", true)
            prefs.edit().putBoolean("enabled", !cur).commit()
            makeWorldReadable()
            toggleBtn.text = if (!cur)
                "Вимкнути заміну (лишити стандартний сірий)" else "Увімкнути заміну"
            statusText.text = "Змінено. Перезапускаю SystemUI..."
            restartSystemUiAsRoot { ok, msg -> statusText.text = "Змінено. $msg" }
        }

        makeWorldReadable()

        val scrimDivider = TextView(this).apply {
            text = "\n— Експериментально —"
            setTextColor(pal.text)
            setPadding(0, pad * 2, 0, pad)
        }
        val scrimInfo = TextView(this).apply {
            text = "Додатковий шар затемнення (ScrimController), який лишався " +
                "видимим навіть після налаштування вище. Впливає ТІЛЬКИ на " +
                "шторку над розблокованим домашнім екраном - екран блокування, " +
                "AOD і PIN не чіпає. Значення 0.0-1.0 (0 = зовсім не затемнювати)."
            textSize = 14f
            setTextColor(pal.text)
            setPadding(0, 0, 0, pad)
        }
        val scrimInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("scrim_alpha", 0.0f).toString())
            hint = "0.0"
            setTextColor(pal.text)
            setHintTextColor(pal.hint)
            setBackgroundColor(pal.fieldBackground)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val scrimToggle = Button(this).apply {
            text = if (prefs.getBoolean("scrim_enabled", false))
                "Вимкнути експериментальний шар" else "Увімкнути експериментальний шар"
            setTextColor(pal.text)
            setBackgroundColor(pal.buttonBackground)
        }
        val scrimStatus = TextView(this).apply {
            setTextColor(pal.secondaryText)
            setPadding(0, pad, 0, 0)
        }
        scrimToggle.setOnClickListener {
            val newAlpha = scrimInput.text.toString().trim().toFloatOrNull()
            if (newAlpha == null || newAlpha < 0f || newAlpha > 1f) {
                scrimInput.error = "Число від 0.0 до 1.0"
                return@setOnClickListener
            }
            val curEnabled = prefs.getBoolean("scrim_enabled", false)
            prefs.edit()
                .putFloat("scrim_alpha", newAlpha)
                .putBoolean("scrim_enabled", !curEnabled)
                .commit()
            makeWorldReadable()
            scrimToggle.text = if (!curEnabled)
                "Вимкнути експериментальний шар" else "Увімкнути експериментальний шар"
            scrimStatus.text = "Змінено (${!curEnabled}, alpha=$newAlpha). Перезапускаю SystemUI..."
            restartSystemUiAsRoot { ok, msg -> scrimStatus.text = "Змінено. $msg" }
        }

        val debugHint = TextView(this).apply {
            text = "\nЯкщо щось не працює: LSPosed Manager -> Logs -> шукай \"ShadeTweak\"." +
                "\nАвтоматичний перезапуск SystemUI потребує окремого дозволу root " +
                "для цього застосунку (запит KernelSU при першому натисканні)."
            textSize = 12f
            setTextColor(pal.secondaryText)
            setPadding(0, pad, 0, 0)
        }

        root.addView(header)
        root.addView(info)
        root.addView(input)
        root.addView(saveBtn)
        root.addView(toggleBtn)
        root.addView(preview)
        root.addView(statusText)

        root.addView(scrimDivider)
        root.addView(scrimInfo)
        root.addView(scrimInput)
        root.addView(scrimToggle)
        root.addView(scrimStatus)
        root.addView(debugHint)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.background)
            addView(root)
        })
    }
}
