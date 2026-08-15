/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import android.app.Application
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global crash handler that persists the stack trace to a file inside
 * the app's external files directory, so failures can be shared with an AI or a
 * developer even when no adb/logcat capture is available.
 */
class MeshKachoUtilityApp : Application() {

    companion object {
        const val LOG_DIR = "logs"
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile(thread, throwable)
            } catch (ignored: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val dir = File(baseDir, LOG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$timestamp.txt")
        FileWriter(file).use { writer ->
            writer.write("MeshKachoUtility crash report\n")
            writer.write("Time: $timestamp\n")
            writer.write("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            writer.write("Thread: ${thread.name}\n\n")
            writer.write(throwable.stackTraceToString())
            var cause = throwable.cause
            while (cause != null) {
                writer.write("\nCaused by: ${cause.javaClass.name}: ${cause.message}\n")
                writer.write(cause.stackTraceToString())
                cause = cause.cause
            }
        }
    }
}
