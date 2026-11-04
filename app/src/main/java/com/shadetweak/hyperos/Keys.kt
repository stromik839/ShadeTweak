package com.shadetweak.hyperos

/**
 * Общие константы для приложения (экран настроек) и для хука внутри SystemUI.
 *
 * Настройки передаются через Settings.Global, а не через XSharedPreferences:
 *  - SystemUI (uid system) читает Settings.Global всегда, без SELinux-ограничений
 *    на чужие /data/data/... (именно из-за них старая версия часто читала
 *    дефолтные значения вместо сохранённых — цвет "не менялся");
 *  - на ключи можно повесить ContentObserver, поэтому новый цвет применяется
 *    сразу, без перезапуска SystemUI.
 */
object Keys {
    const val ENABLED = "shadetweak_enabled"          // "1" / "0"
    const val COLOR = "shadetweak_color"              // "AARRGGBB"
    const val FORCE_SOLID = "shadetweak_force_solid"  // "1" / "0", по умолчанию "1"
    const val MODAL = "shadetweak_modal"              // "1" / "0", по умолчанию "1"

    /** Пишет сам хук из SystemUI: что он видит на этом устройстве. */
    const val STATUS = "shadetweak_status"

    val CONFIG = arrayOf(ENABLED, COLOR, FORCE_SOLID, MODAL)

    /** Системный "серый" фон HyperOS: color/shade_solid_background_color. */
    const val SYSTEM_COLOR = 0xFF6E6E6E.toInt()

    /** Принимает "AARRGGBB", "RRGGBB" (тогда альфа = FF), с "#" или без. */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if (s.isEmpty() || s.any { Character.digit(it, 16) < 0 }) return null
        return when (s.length) {
            6 -> (0xFF000000L or s.toLong(16)).toInt()
            8 -> s.toLong(16).toInt()
            else -> null
        }
    }

    fun formatColor(color: Int): String = String.format("%08X", color)
}
