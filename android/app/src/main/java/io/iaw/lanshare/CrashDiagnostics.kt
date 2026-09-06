package io.iaw.lanshare

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

class WifiShareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The recovery process must neither initialize business state nor overwrite its crash evidence.
        if (getProcessName() == "$packageName:diagnostics") return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashDiagnostics.record(this, error, fatal = true)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}

internal object CrashDiagnostics {
    fun record(context: Context, error: Throwable, fatal: Boolean = false) {
        runCatching {
            val file = AtomicFile(File(context.filesDir, if (fatal) "last-crash.txt" else "last-failure.txt"))
            val output = file.startWrite()
            try {
                output.write(("${Instant.now()}\n" + CrashTrace.format(error)).toByteArray())
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun report(context: Context): String = buildString {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("[Version unavailable]")
        appendLine("WifiShare $version")
        appendLine("Report time: ${Instant.now()}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        for (name in listOf("last-crash.txt", "last-failure.txt")) {
            appendLine("\n$name")
            appendLine(DiagnosticLogReader.read {
                val file = File(context.filesDir, name)
                // Do not use AtomicFile.openRead(): it can rename/delete files across processes.
                val backup = File(context.filesDir, "$name.bak")
                when {
                    backup.isFile -> backup.bufferedReader()
                    file.isFile -> file.bufferedReader()
                    else -> null
                }
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendLine("\nRecent system process exits:")
            runCatching {
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                if (exits.isEmpty()) appendLine("[No system exit records]")
                exits.forEach { exit ->
                    appendLine("Exit ${Instant.ofEpochMilli(exit.timestamp)}: reason=${exit.reason}, status=${exit.status}, importance=${exit.importance}")
                }
            }.onFailure {
                appendLine("[System exit records unavailable]")
            }
        }
    }
}
