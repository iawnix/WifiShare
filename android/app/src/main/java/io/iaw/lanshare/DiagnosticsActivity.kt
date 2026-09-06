package io.iaw.lanshare

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

/** Deliberately uses framework views, a separate process, and no business settings. */
class DiagnosticsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private lateinit var copyButton: Button
    private lateinit var saveButton: Button
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        SystemBars.applyInsetPadding(findViewById(R.id.diagnosticsRoot))
        reportView = findViewById(R.id.diagnosticsReport)
        statusView = findViewById(R.id.diagnosticsStatus)
        copyButton = findViewById(R.id.diagnosticsCopy)
        saveButton = findViewById(R.id.diagnosticsSave)
        copyButton.setOnClickListener { copyReport() }
        saveButton.setOnClickListener { selectExportFile() }

        // Never read incoming intents or return reports to an external caller.
        worker.execute {
            val result = runCatching { CrashDiagnostics.report(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { value ->
                    report = value
                    reportView.text = value
                    statusView.text = ""
                    copyButton.isEnabled = true
                    saveButton.isEnabled = true
                }.onFailure {
                    statusView.setText(R.string.diagnostics_failed)
                }
            }
        }
    }

    private fun copyReport() {
        val value = report ?: return
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("WifiShare diagnostics", value))
        }.isSuccess
        statusView.setText(if (copied) R.string.diagnostics_copied else R.string.diagnostics_export_failed)
    }

    @Suppress("DEPRECATION")
    private fun selectExportFile() {
        if (report == null) return
        val opened = runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "WifiShare-diagnostics.txt")
                },
                EXPORT_REQUEST,
            )
        }.isSuccess
        if (!opened) statusView.setText(R.string.diagnostics_export_failed)
    }

    @Deprecated("Framework Activity result API keeps this recovery screen independent of AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        saveButton.isEnabled = false
        val snapshot = report
        // A recreated activity may still be loading. Serialize behind that load and read again if needed.
        worker.execute {
            val saved = runCatching {
                val value = snapshot ?: CrashDiagnostics.report(applicationContext)
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: error("No output stream")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(value) }
            }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saveButton.isEnabled = report != null
                statusView.setText(if (saved) R.string.diagnostics_saved else R.string.diagnostics_export_failed)
            }
        }
    }

    override fun onDestroy() {
        // Allow a user-approved export already in progress to finish after a rotation or Back.
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        private const val EXPORT_REQUEST = 1
    }
}
