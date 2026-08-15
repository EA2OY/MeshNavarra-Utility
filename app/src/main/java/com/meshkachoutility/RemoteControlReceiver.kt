/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remote control bridge (debug/test accelerator): listens for the explicit
 * broadcast `com.meshkachoutility.REMOTE` (e.g. driven from the PC with
 * `adb shell am broadcast -a com.meshkachoutility.REMOTE --es cmd <action>
 * [--ei num N] [--es arg ".."] [--es arg2 ".."]`) and forwards it to
 * MainActivity via the static [handler]. If the activity is not running the
 * receiver does a clean no-op and logs it to app_log.txt.
 *
 * This is a test utility; the receiver is exported so adb can reach it.
 */
class RemoteControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.meshkachoutility.REMOTE"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_NUM = "num"
        const val EXTRA_ARG = "arg"
        const val EXTRA_ARG2 = "arg2"

        /**
         * Set by MainActivity while it is alive. Signature:
         * (cmd, numArg, arg, arg2).
         */
        @Volatile
        var handler: ((String, Int, String, String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: ""
        val num = intent.getIntExtra(EXTRA_NUM, -1)
        val arg = intent.getStringExtra(EXTRA_ARG) ?: ""
        val arg2 = intent.getStringExtra(EXTRA_ARG2) ?: ""
        val h = handler
        if (h == null) {
            appendNoActivityLog(context, "REMOTE: $cmd no ejecutado (app no en ejecución)")
            return
        }
        h(cmd, num, arg, arg2)
    }

    private fun appendNoActivityLog(context: Context, message: String) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(baseDir, MeshKachoUtilityApp.LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "app_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            FileWriter(file, true).use { it.append("$timestamp $message\n") }
        } catch (ignored: Exception) {
        }
    }
}
