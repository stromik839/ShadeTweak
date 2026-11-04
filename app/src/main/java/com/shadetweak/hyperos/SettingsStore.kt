package com.shadetweak.hyperos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Запись настроек в Settings.Global.
 *
 * Для записи нужно разрешение WRITE_SECURE_SETTINGS. Его один раз выдаём себе через
 * root (`pm grant`), дальше пишем напрямую — быстро и без повторных запросов root.
 * Если не вышло, пишем через `su -c settings put global ...`.
 */
object SettingsStore {

    class Result(val ok: Boolean, val message: String)

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Вызывать не на главном потоке (может ждать подтверждения root). */
    fun write(ctx: Context, values: Map<String, String>): Result {
        if (hasPermission(ctx) && putDirect(ctx, values)) return Result(true, "сохранено")

        val grant = Root.run("pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        if (grant.ok && putDirect(ctx, values)) return Result(true, "сохранено (разрешение выдано через root)")

        val script = values.entries.joinToString("\n") { "settings put global ${it.key} ${it.value}" }
        val shell = Root.run(script)
        return if (shell.ok) {
            Result(true, "сохранено через su")
        } else {
            Result(false, "не удалось сохранить: ${shell.output}")
        }
    }

    private fun putDirect(ctx: Context, values: Map<String, String>): Boolean = try {
        values.all { (k, v) -> Settings.Global.putString(ctx.contentResolver, k, v) }
    } catch (t: Throwable) {
        false
    }

    /** Статус, который пишет хук из SystemUI. null — нет данных или нет доступа на чтение. */
    fun readStatus(ctx: Context): Map<String, String>? = try {
        Settings.Global.getString(ctx.contentResolver, Keys.STATUS)
            ?.split(';')
            ?.mapNotNull { part ->
                val i = part.indexOf('=')
                if (i > 0) part.substring(0, i) to part.substring(i + 1) else null
            }
            ?.toMap()
    } catch (t: Throwable) {
        null
    }
}
