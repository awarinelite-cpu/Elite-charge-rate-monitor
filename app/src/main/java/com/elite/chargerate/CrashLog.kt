package com.elite.chargerate

import android.content.Context
import android.util.Log
import java.io.File

/** Saves the stack trace of an uncaught crash so the next launch can show it. */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(
                    "Thread: ${thread.name}\n" + Log.getStackTraceString(error)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
