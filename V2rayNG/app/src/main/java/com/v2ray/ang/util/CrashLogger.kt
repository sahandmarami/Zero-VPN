package com.v2ray.ang.util

import android.content.Context
import android.os.Process
import com.v2ray.ang.AppConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zero VPN: catches uncaught exceptions in every process (main, :daemon, :tasks, :bg),
 * writes a small report file before the process dies, then hands over to the system
 * handler so the normal crash flow is unchanged.
 *
 * The report is picked up by [takePendingReport] the next time the main UI starts,
 * which offers the user to copy it — turning "برنامه به مشکل خورده" into a
 * diagnosable report instead of a dead end.
 */
object CrashLogger {

    private const val DIR_NAME = "zero_crash"
    private const val MAX_FILES = 3

    /** Installs the global crash file writer. Safe to call from any process. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context.applicationContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let the reporter itself break the crash flow.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

        // Age out old reports, keep the newest MAX_FILES-1 before writing the new one.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES - 1)?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val processName = try {
            ApplicationInfoProvider.currentProcessName(context)
        } catch (_: Throwable) {
            "unknown"
        }

        val sb = StringBuilder()
        sb.appendLine("Zero VPN crash report")
        sb.appendLine("time: $stamp")
        sb.appendLine("process: $processName (pid=${Process.myPid()})")
        sb.appendLine("thread: ${thread.name}")
        sb.appendLine("version: ${AppConfig.ANG_PACKAGE}")
        sb.appendLine()
        sb.appendLine(android.util.Log.getStackTraceString(throwable))

        File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(sb.toString())
    }

    /**
     * Returns the newest crash report (if any) and deletes all stored reports,
     * so the user is only asked once per crash.
     */
    fun takePendingReport(context: Context): String? {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
        val newest = files.firstOrNull() ?: return null
        val text = try {
            newest.readText()
        } catch (_: Throwable) {
            null
        }
        files.forEach { it.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}

/** Small helper kept separate so unit tests / non-Android paths stay simple. */
private object ApplicationInfoProvider {
    fun currentProcessName(context: Context): String {
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.let { return it.processName }
        return context.packageName
    }
}
