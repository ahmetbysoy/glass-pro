package com.glasspro.tracker

import android.app.Application
import android.util.Log
import com.glasspro.tracker.core.di.ServiceLocator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application entry point.
 *
 * Two defensive measures are installed before anything else runs:
 *  1. A global uncaught-exception handler that persists the stack trace to
 *     crash.log inside the app files dir. If the app ever dies, the next
 *     launch shows the trace in a dialog (see MainActivity) so the failure
 *     can be diagnosed on-device without adb.
 *  2. All background coroutines run on a scope with a CoroutineExceptionHandler
 *     that logs instead of letting a single failed network task kill the app.
 */
class GlassProApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        serviceLocator = ServiceLocator(this)
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashLog = File(filesDir, CRASH_LOG_NAME)
                crashLog.appendText(
                    "=== ${System.currentTimeMillis()} [${thread.name}] ===\n" +
                        "${throwable.javaClass.name}: ${throwable.message}\n$sw\n\n"
                )
                Log.e(TAG, "Uncaught crash: ${throwable.message}", throwable)
            } catch (_: Exception) {
                // Never mask the original crash with a logging failure.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun readCrashLog(): String? {
        val file = File(filesDir, CRASH_LOG_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clearCrashLog() {
        runCatching { File(filesDir, CRASH_LOG_NAME).delete() }
    }

    companion object {
        private const val TAG = "GlassPro"
        const val CRASH_LOG_NAME = "crash.log"
    }
}
