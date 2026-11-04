package com.shadetweak.hyperos

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val prefsName = "shade_tweak_prefs"
    private lateinit var prefs: SharedPreferences

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var color = DEFAULT_COLOR
    private var updatingViews = false

    private lateinit var preview: ColorPreviewView
    private lateinit var hexInput: EditText
    private lateinit var colorInfo: TextView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var forceSolidSwitch: Switch
    private lateinit var modalSwitch: Switch
    private val sliders = arrayOfNulls<SeekBar>(4)       // A, R, G, B
    private val sliderLabels = arrayOfNulls<TextView>(4)

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        color = loadColor()
        setContentView(buildUi())
        syncViewsFromColor(fromHex = false, fromSlider = false)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Shade Tweak"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(caption("Цвет и прозрачность фона шторки HyperOS, когда блюр выключен"))

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x1F808080)
            }
            text = "Статус: проверяю…"
        }
        root.addView(statusText, matchWrap(top = dp(16)))

        enabledSwitch = Switch(this).apply {
            text = "Заменить фон шторки"
            textSize = 16f
            isChecked = prefs.getBoolean(P_ENABLED, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(P_ENABLED, checked).apply()
                applyNow()
            }
        }
        root.addView(enabledSwitch, matchWrap(top = dp(16)))

        preview = ColorPreviewView(this, dark)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)).apply {
            topMargin = dp(12)
        })

        colorInfo = caption("")
        root.addView(colorInfo, matchWrap(top = dp(6)))

        // --- HEX
        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hexInput = EditText(this).apply {
            hint = "AARRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(9))
            imeOptions = EditorInfo.IME_ACTION_DONE
            typeface = Typeface.MONOSPACE
            setSingleLine()
            // живое превью при вводе; сохраняется по OK / Готово
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updatingViews) return
                    val parsed = Keys.parseColor(s?.toString()) ?: return
                    color = parsed
                    syncViewsFromColor(fromHex = true, fromSlider = false)
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyHex()
                    true
                } else {
                    false
                }
            }
        }
        hexRow.addView(hexInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hexRow.addView(Button(this).apply {
            text = "OK"
            setOnClickListener { applyHex() }
        })
        root.addView(hexRow, matchWrap(top = dp(4)))

        // --- слайдеры
        for (i in 0 until 4) {
            val label = TextView(this).apply { textSize = 13f }
            sliderLabels[i] = label
            root.addView(label, matchWrap(top = dp(if (i == 0) 12 else 6)))
            val bar = SeekBar(this).apply {
                max = 255
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser || updatingViews) return
                        color = colorFromSliders()
                        syncViewsFromColor(fromHex = false, fromSlider = true)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        saveColor()
                        applyNow()
                    }
                })
            }
            sliders[i] = bar
            root.addView(bar, matchWrap())
        }

        // --- готовые варианты
        root.addView(header("Быстрый выбор"), matchWrap(top = dp(16)))
        val presetsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, c) in PRESETS) {
            presetsRow.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener {
                    color = c
                    syncViewsFromColor(fromHex = false, fromSlider = false)
                    saveColor()
                    if (!enabledSwitch.isChecked) {
                        enabledSwitch.isChecked = true // сам вызовет applyNow()
                    } else {
                        applyNow()
                    }
                }
            })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetsRow)
        }, matchWrap(top = dp(4)))

        // --- дополнительные настройки
        root.addView(header("Дополнительно"), matchWrap(top = dp(20)))
        forceSolidSwitch = Switch(this).apply {
            text = "Отключать скриншот-блюр"
            isChecked = prefs.getBoolean(P_FORCE_SOLID, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(P_FORCE_SOLID, checked).apply()
                applyNow(extra = "Чтобы это заработало, перезапусти SystemUI.")
            }
        }
        root.addView(forceSolidSwitch, matchWrap(top = dp(4)))
        root.addView(caption(
            "На некоторых устройствах HyperOS вместо блюра рисует размытый скриншот " +
                "с серым фильтром — в этом режиме свой цвет вообще не применяется. " +
                "Переключатель возвращает обычный сплошной фон (нужен перезапуск SystemUI)."
        ))

        modalSwitch = Switch(this).apply {
            text = "Тот же цвет для окна уведомления"
            isChecked = prefs.getBoolean(P_MODAL, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(P_MODAL, checked).apply()
                applyNow()
            }
        }
        root.addView(modalSwitch, matchWrap(top = dp(12)))
        root.addView(caption("Фон, который появляется за раскрытым уведомлением (долгое нажатие)."))

        // --- кнопки
        root.addView(Button(this).apply {
            text = "Применить"
            setOnClickListener {
                saveColor()
                applyNow()
            }
        }, matchWrap(top = dp(20)))
        root.addView(Button(this).apply {
            text = "Перезапустить SystemUI"
            setOnClickListener { restartSystemUi() }
        }, matchWrap(top = dp(4)))

        resultText = caption("")
        root.addView(resultText, matchWrap(top = dp(8)))

        root.addView(caption(
            "Цвет применяется сразу, без перезапуска. При первом сохранении нужен root " +
                "(KernelSU: Суперпользователь → Shade Tweak; Magisk покажет запрос). Он нужен " +
                "один раз, чтобы выдать приложению право писать настройки " +
                "(WRITE_SECURE_SETTINGS).\n\n" +
                "Если не работает: LSPosed → Модули → Shade Tweak включён, в области " +
                "отмечен «Системный интерфейс», затем перезапусти SystemUI. Логи: " +
                "LSPosed → Логи → «ShadeTweak»."
        ), matchWrap(top = dp(16)))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.75f
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun matchWrap(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    // ---------------------------------------------------------------- цвет

    private fun colorFromSliders(): Int {
        val v = IntArray(4) { sliders[it]?.progress ?: 0 }
        return Color.argb(v[0], v[1], v[2], v[3])
    }

    private fun syncViewsFromColor(fromHex: Boolean, fromSlider: Boolean) {
        updatingViews = true
        try {
            preview.color = color
            val parts = intArrayOf(Color.alpha(color), Color.red(color), Color.green(color), Color.blue(color))
            for (i in 0 until 4) {
                if (!fromSlider) sliders[i]?.progress = parts[i]
                sliderLabels[i]?.text = if (i == 0) {
                    "${SLIDER_NAMES[i]}: ${percent(parts[0])}%" + when (parts[0]) {
                        0 -> " (полностью прозрачно)"
                        255 -> " (без прозрачности)"
                        else -> ""
                    }
                } else {
                    "${SLIDER_NAMES[i]}: ${parts[i]}"
                }
            }
            if (!fromHex) hexInput.setText(Keys.formatColor(color))
            colorInfo.text = "#${Keys.formatColor(color)} · непрозрачность ${percent(parts[0])}%"
        } finally {
            updatingViews = false
        }
    }

    private fun percent(alpha: Int) = Math.round(alpha * 100f / 255f)

    private fun applyHex() {
        val parsed = Keys.parseColor(hexInput.text.toString())
        if (parsed == null) {
            hexInput.error = "Формат AARRGGBB, например 00000000 или 80000000"
            return
        }
        color = parsed
        syncViewsFromColor(fromHex = false, fromSlider = false)
        saveColor()
        if (!enabledSwitch.isChecked) enabledSwitch.isChecked = true else applyNow()
    }

    private fun loadColor(): Int {
        if (prefs.contains(P_COLOR)) return prefs.getInt(P_COLOR, DEFAULT_COLOR)
        // перенос со старой версии (строка AARRGGBB)
        return Keys.parseColor(prefs.getString("argb", null)) ?: DEFAULT_COLOR
    }

    private fun saveColor() {
        prefs.edit().putInt(P_COLOR, color).apply()
    }

    // ------------------------------------------------------------ применение

    private fun applyNow(extra: String? = null) {
        val values = linkedMapOf(
            Keys.COLOR to Keys.formatColor(color),
            Keys.FORCE_SOLID to if (forceSolidSwitch.isChecked) "1" else "0",
            Keys.MODAL to if (modalSwitch.isChecked) "1" else "0",
            // ENABLED последним: SystemUI перечитает всё по одному событию
            Keys.ENABLED to if (enabledSwitch.isChecked) "1" else "0",
        )
        resultText.text = "Сохраняю…"
        io.execute {
            val res = SettingsStore.write(applicationContext, values)
            main.post {
                if (isFinishing) return@post
                resultText.text = buildString {
                    append(if (res.ok) "✓ " else "✗ ")
                    append(res.message)
                    if (res.ok && enabledSwitch.isChecked) append(". Открой шторку — цвет уже должен быть новым.")
                    if (!res.ok) {
                        append("\n\nБез root можно выдать право один раз через ПК:\n")
                        append("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
                    }
                    if (extra != null) append("\n").append(extra)
                }
                main.postDelayed({ refreshStatus() }, 800)
            }
        }
    }

    private fun restartSystemUi() {
        resultText.text = "Перезапускаю SystemUI…"
        io.execute {
            val res = Root.restartSystemUi()
            main.post {
                if (isFinishing) return@post
                resultText.text = if (res.ok) "✓ SystemUI перезапущен" else "✗ ${res.output}"
                main.postDelayed({ refreshStatus() }, 5000)
            }
        }
    }

    private fun refreshStatus() {
        io.execute {
            val status = SettingsStore.readStatus(applicationContext)
            val canRead = SettingsStore.hasPermission(applicationContext)
            main.post {
                if (!isFinishing) statusText.text = describeStatus(status, canRead)
            }
        }
    }

    private fun describeStatus(s: Map<String, String>?, canRead: Boolean): String {
        if (s == null) {
            return if (!canRead) {
                "Статус: пока неизвестен. Нажми «Применить» — после выдачи разрешения здесь " +
                    "появится, что модуль видит в SystemUI."
            } else {
                "Статус: SystemUI не отвечает. Проверь, что модуль включён в LSPosed " +
                    "(область: «Системный интерфейс»), и перезапусти SystemUI."
            }
        }
        val lines = ArrayList<String>()
        lines += "Модуль работает в SystemUI (v${s["v"] ?: "?"}, хуков: ${s["hooks"] ?: "?"})"
        when {
            s["miblur"] == "1" || s["winblur"] == "1" ->
                lines += "⚠ Сейчас в шторке включён системный блюр — свой цвет рисуется только " +
                    "без блюра. Выключи блюр (например через deviceLevelList)."
            s["screenshot"] == "1" ->
                lines += "⚠ Включён скриншот-блюр: свой цвет не виден. Включи «Отключать " +
                    "скриншот-блюр» и перезапусти SystemUI."
            else ->
                lines += "Фон шторки сплошной — цвет применяется сразу."
        }
        if (s["forced"] == "1") lines += "Скриншот-блюр отключён модулем."
        if (s["restart"] == "1") lines += "Нужен перезапуск SystemUI."
        return lines.joinToString("\n")
    }

    companion object {
        private const val P_ENABLED = "enabled"
        private const val P_COLOR = "color"
        private const val P_FORCE_SOLID = "force_solid"
        private const val P_MODAL = "modal"

        private const val DEFAULT_COLOR = 0x80000000.toInt()

        private val SLIDER_NAMES = arrayOf("Непрозрачность", "Красный", "Зелёный", "Синий")

        private val PRESETS = listOf(
            "Прозрачный" to 0x00000000,
            "Тень 30%" to 0x4D000000,
            "Тёмный 60%" to 0x99000000.toInt(),
            "Чёрный" to 0xFF000000.toInt(),
            "Белый 25%" to 0x40FFFFFF,
            "Системный" to Keys.SYSTEM_COLOR,
        )
    }
}
