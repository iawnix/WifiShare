package io.iaw.lanshare

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup

class SettingsActivity : Activity() {
    private lateinit var savedServersListView: LinearLayout
    private lateinit var activeServerStatusView: TextView
    private lateinit var serverNameView: EditText
    private lateinit var baseUrlView: EditText
    private lateinit var authTokenView: EditText
    private lateinit var fingerprintView: EditText
    private lateinit var statusView: TextView
    private lateinit var activateServerButton: Button
    private lateinit var deleteServerButton: Button
    private lateinit var newServerButton: Button
    private lateinit var saveButton: Button
    private lateinit var backButton: ImageButton

    private lateinit var settingsStore: SettingsStore
    private var savedProfiles: List<TransferConfig> = emptyList()
    private var selectedProfileKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.applyInsetPadding(findViewById(R.id.settingsScroll), includeIme = true)

        settingsStore = SettingsStore(this)
        bindViews()
        installImeAwareFocus()
        attachListeners()
        restoreSavedConfig()
    }

    private fun bindViews() {
        savedServersListView = findViewById(R.id.savedServersList)
        activeServerStatusView = findViewById(R.id.activeServerStatusText)
        serverNameView = findViewById(R.id.serverNameInput)
        baseUrlView = findViewById(R.id.baseUrlInput)
        authTokenView = findViewById(R.id.authTokenInput)
        fingerprintView = findViewById(R.id.fingerprintInput)
        statusView = findViewById(R.id.settingsStatusText)
        activateServerButton = findViewById(R.id.activateServerButton)
        deleteServerButton = findViewById(R.id.deleteServerButton)
        newServerButton = findViewById(R.id.newServerButton)
        saveButton = findViewById(R.id.saveButton)
        backButton = findViewById(R.id.backButton)
    }

    private fun restoreSavedConfig() {
        refreshSavedServers(settingsStore.loadActive()?.profileKey())
    }

    private fun attachListeners() {
        activateServerButton.setOnClickListener {
            val selected = selectedSavedConfig() ?: return@setOnClickListener
            if (settingsStore.setActive(selected)) {
                val message = getString(R.string.server_switched, selected.serverName)
                statusView.text = message
                WifiShareWidgetProvider.updateAllWidgets(this)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                refreshSavedServers(selected.profileKey())
            }
        }

        deleteServerButton.setOnClickListener {
            val selected = selectedSavedConfig() ?: return@setOnClickListener
            if (settingsStore.delete(selected.profileKey())) {
                val message = getString(R.string.server_deleted, selected.serverName)
                statusView.text = message
                WifiShareWidgetProvider.updateAllWidgets(this)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                refreshSavedServers(settingsStore.loadActive()?.profileKey())
            }
        }

        newServerButton.setOnClickListener {
            selectedProfileKey = null
            clearConfig()
            renderSavedServers()
            activeServerStatusView.text = getString(R.string.new_server_status)
            activateServerButton.isEnabled = false
            activateServerButton.alpha = 0.55f
            deleteServerButton.isEnabled = false
            deleteServerButton.alpha = 0.55f
        }

        saveButton.setOnClickListener {
            val config = currentConfig()
            if (!config.isComplete()) {
                statusView.text = getString(R.string.invalid_config)
                return@setOnClickListener
            }
            settingsStore.saveAndActivate(config, replaceProfileKey = selectedProfileKey)
            val message = getString(R.string.config_saved, config.serverName)
            statusView.text = message
            WifiShareWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refreshSavedServers(config.profileKey())
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun refreshSavedServers(selectKey: String?) {
        savedProfiles = settingsStore.loadAll()
        selectedProfileKey = if (savedProfiles.any { it.profileKey() == selectKey }) {
            selectKey
        } else {
            settingsStore.loadActive()?.profileKey() ?: savedProfiles.firstOrNull()?.profileKey()
        }

        renderSavedServers()
        val selected = selectedSavedConfig()
        if (selected != null) {
            populateConfig(selected)
            updateActiveStatus(selected)
        } else {
            activeServerStatusView.text = getString(R.string.no_saved_servers)
            clearConfig()
        }
        val hasSelection = selected != null
        activateServerButton.isEnabled = hasSelection
        activateServerButton.alpha = if (hasSelection) 1.0f else 0.55f
        deleteServerButton.isEnabled = hasSelection
        deleteServerButton.alpha = if (hasSelection) 1.0f else 0.55f
    }

    private fun renderSavedServers() {
        savedServersListView.removeAllViews()
        if (savedProfiles.isEmpty()) {
            savedServersListView.addView(serverListButton(getString(R.string.no_saved_servers), false, false))
            return
        }

        val activeKey = settingsStore.loadActive()?.profileKey()
        savedProfiles.forEach { config ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val isSelected = config.profileKey() == selectedProfileKey
            val isActive = config.profileKey() == activeKey
            val mainButton = serverListButton(serverListLabel(config, isActive), isActive || isSelected, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    selectedProfileKey = config.profileKey()
                    populateConfig(config)
                    updateActiveStatus(config)
                    renderSavedServers()
                    activateServerButton.isEnabled = true
                    activateServerButton.alpha = 1.0f
                    deleteServerButton.isEnabled = true
                    deleteServerButton.alpha = 1.0f
                }
            }

            val switchButton = Button(this).apply {
                text = getString(R.string.use_selected_server)
                setAllCaps(false)
                textSize = 13f
                maxLines = 1
                isEnabled = !isActive
                alpha = if (isActive) 0.55f else 1.0f
                setTextColor(getColor(if (isActive) R.color.lss_muted else R.color.lss_teal))
                setBackgroundResource(R.drawable.button_outline)
                layoutParams = LinearLayout.LayoutParams(dp(90), dp(46))
                setOnClickListener {
                    if (settingsStore.setActive(config)) {
                        val message = getString(R.string.server_switched, config.serverName)
                        selectedProfileKey = config.profileKey()
                        populateConfig(config)
                        statusView.text = message
                        WifiShareWidgetProvider.updateAllWidgets(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                        refreshSavedServers(config.profileKey())
                    }
                }
            }

            row.addView(mainButton)
            row.addView(switchButton)
            savedServersListView.addView(row)
        }
    }

    private fun selectedSavedConfig(): TransferConfig? {
        val key = selectedProfileKey ?: return null
        return savedProfiles.firstOrNull { it.profileKey() == key }
    }

    private fun populateConfig(config: TransferConfig) {
        serverNameView.setText(config.serverName)
        baseUrlView.setText(config.baseUrl)
        authTokenView.setText(config.authToken)
        fingerprintView.setText(config.certificateSha256)
    }

    private fun clearConfig() {
        serverNameView.text.clear()
        baseUrlView.text.clear()
        authTokenView.text.clear()
        fingerprintView.text.clear()
    }

    private fun updateActiveStatus(selected: TransferConfig) {
        val active = settingsStore.loadActive()
        activeServerStatusView.text = if (active?.profileKey() == selected.profileKey()) {
            getString(R.string.active_server_status, selected.serverName)
        } else {
            getString(R.string.selected_server_status, selected.serverName)
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

    private fun installImeAwareFocus() {
        listOf(serverNameView, baseUrlView, authTokenView, fingerprintView).forEach { field ->
            field.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    keepFocusedInputVisible(view)
                }
            }
        }
    }

    private fun keepFocusedInputVisible(view: View) {
        view.postDelayed({
            val extraBottom = dp(96)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height + extraBottom), true)
        }, 180)
    }

    private fun serverListButton(label: String, highlighted: Boolean, enabled: Boolean): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isEnabled = enabled
            alpha = if (enabled) 1.0f else 0.55f
            setTextColor(getColor(if (highlighted) android.R.color.white else R.color.lss_teal))
            setBackgroundResource(if (highlighted) R.drawable.button_secondary else R.drawable.button_outline)
        }
    }

    private fun serverListLabel(config: TransferConfig, active: Boolean): String {
        val name = config.serverName.ifBlank { config.baseUrl }
        return if (active) {
            "当前：$name"
        } else {
            name
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
