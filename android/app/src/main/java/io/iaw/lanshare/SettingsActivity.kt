package io.iaw.lanshare

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var serverNameView: EditText
    private lateinit var baseUrlView: EditText
    private lateinit var authTokenView: EditText
    private lateinit var fingerprintView: EditText
    private lateinit var statusView: TextView
    private lateinit var saveButton: Button
    private lateinit var backButton: Button

    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsStore = SettingsStore(this)
        bindViews()
        restoreSavedConfig()
        attachListeners()
    }

    private fun bindViews() {
        serverNameView = findViewById(R.id.serverNameInput)
        baseUrlView = findViewById(R.id.baseUrlInput)
        authTokenView = findViewById(R.id.authTokenInput)
        fingerprintView = findViewById(R.id.fingerprintInput)
        statusView = findViewById(R.id.settingsStatusText)
        saveButton = findViewById(R.id.saveButton)
        backButton = findViewById(R.id.backButton)
    }

    private fun restoreSavedConfig() {
        val saved = settingsStore.load() ?: return
        serverNameView.setText(saved.serverName)
        baseUrlView.setText(saved.baseUrl)
        authTokenView.setText(saved.authToken)
        fingerprintView.setText(saved.certificateSha256)
    }

    private fun attachListeners() {
        saveButton.setOnClickListener {
            val config = currentConfig()
            if (!config.isComplete()) {
                statusView.text = getString(R.string.invalid_config)
                return@setOnClickListener
            }
            settingsStore.save(config)
            val message = getString(R.string.config_saved, config.serverName)
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun currentConfig(): TransferConfig {
        return TransferConfig(
            serverName = serverNameView.text.toString().trim(),
            baseUrl = TransferConfig.normalizeBaseUrl(baseUrlView.text.toString()),
            authToken = TransferConfig.normalizeAuthToken(authTokenView.text.toString()),
            certificateSha256 = TransferConfig.normalizeFingerprint(fingerprintView.text.toString()),
        )
    }
}
