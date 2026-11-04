package com.shadetweak.hyperos

import java.util.concurrent.TimeUnit

/** Запуск команд через su (KernelSU / Magisk). Вызывать только НЕ на главном потоке. */
object Root {

    class Result(val ok: Boolean, val output: String)

    fun run(script: String, timeoutSec: Long = 30): Result {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                } catch (ignored: Throwable) {
                }
            }
            reader.start()
            process.outputStream.bufferedWriter().use {
                it.write(script)
                it.write("\nexit\n")
            }
            // timeout побольше: пользователь может подтверждать запрос root
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroy()
                return Result(false, "su не ответил за $timeoutSec с")
            }
            reader.join(1000)
            val code = process.exitValue()
            Result(code == 0, output.toString().trim().ifEmpty { "код $code" })
        } catch (t: Throwable) {
            Result(false, "root недоступен: ${t.message ?: t}")
        }
    }

    fun restartSystemUi(): Result = run("killall com.android.systemui || pkill -f com.android.systemui")
}
