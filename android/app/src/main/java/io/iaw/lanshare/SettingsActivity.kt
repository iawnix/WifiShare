package io.iaw.lanshare

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var settingsScrollView: View
    private lateinit var settingsCardView: View
    private lateinit var savedServersListView: LinearLayout
    private lateinit var activeServerStatusView: TextView
    private lateinit var savedServersLabelView: TextView
    private lateinit var editorLabelView: TextView
    private lateinit var serverNameView: EditText
    private lateinit var baseUrlView: EditText
    private lateinit var authTokenView: EditText
    private lateinit var fingerprintView: EditText
    private lateinit var statusView: TextView
    private lateinit var deleteServerButton: ImageButton
    private lateinit var newServerButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var themeToggleButton: ImageButton

    private lateinit var settingsStore: SettingsStore
    private lateinit var themeMode: ThemeModeSetting
    private lateinit var palette: ThemePalette
    private var savedProfiles: List<TransferConfig> = emptyList()
    private var selectedProfileKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        themeMode = settingsStore.loadThemeMode()
        palette = AppTheme.palette(themeMode)
        AppTheme.applyToActivity(this, palette)
        setContentView(R.layout.activity_settings)
        SystemBars.applyInsetPadding(findViewById(R.id.settingsScroll), includeIme = true)

        bindViews()
        applyTheme()
        installImeAwareFocus()
        attachListeners()
        restoreSavedConfig()
    }

    private fun bindViews() {
        settingsScrollView = findViewById(R.id.settingsScroll)
        settingsCardView = findViewById(R.id.settingsCard)
        savedServersListView = findViewById(R.id.savedServersList)
        activeServerStatusView = findViewById(R.id.activeServerStatusText)
        savedServersLabelView = findViewById(R.id.savedServersLabel)
        editorLabelView = findViewById(R.id.editorLabel)
        serverNameView = findViewById(R.id.serverNameInput)
        baseUrlView = findViewById(R.id.baseUrlInput)
        authTokenView = findViewById(R.id.authTokenInput)
        fingerprintView = findViewById(R.id.fingerprintInput)
        statusView = findViewById(R.id.settingsStatusText)
        deleteServerButton = findViewById(R.id.deleteServerButton)
        newServerButton = findViewById(R.id.newServerButton)
        saveButton = findViewById(R.id.saveButton)
        backButton = findViewById(R.id.backButton)
        themeToggleButton = findViewById(R.id.settingsThemeToggleButton)
    }

    private fun restoreSavedConfig() {
        refreshSavedServers(settingsStore.loadActive()?.profileKey())
    }

    private fun attachListeners() {
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
            deleteServerButton.isEnabled = false
            deleteServerButton.alpha = 0.55f
        }

        saveButton.setOnClickListener {
            val config = currentConfig()
            if (!config.isComplete()) {
                statusView.text = getString(R.string.invalid_config)
                return@setOnClickListener
            }
            settingsStore.saveProfile(config, replaceProfileKey = selectedProfileKey)
            val message = getString(R.string.config_saved, config.serverName)
            statusView.text = message
            WifiShareWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refreshSavedServers(config.profileKey())
        }

        backButton.setOnClickListener {
            finish()
        }

        themeToggleButton.setOnClickListener {
            themeMode = themeMode.toggled()
            settingsStore.saveThemeMode(themeMode)
            palette = AppTheme.palette(themeMode)
            AppTheme.applyToActivity(this, palette)
            applyTheme()
            renderSavedServers()
        }
    }

    private fun refreshSavedServers(selectKey: String?) {
        savedProfiles = settingsStore.loadAll()
        selectedProfileKey = if (savedProfiles.any { it.profileKey() == selectKey }) {
            selectKey
        } else {
            savedProfiles.firstOrNull()?.profileKey()
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
        deleteServerButton.isEnabled = hasSelection
        deleteServerButton.alpha = if (hasSelection) 1.0f else 0.55f
    }

    private fun renderSavedServers() {
        savedServersListView.removeAllViews()
        if (savedProfiles.isEmpty()) {
            savedServersListView.addView(serverListButton(getString(R.string.no_saved_servers), false, false))
            return
        }

        savedProfiles.forEach { config ->
            val isSelected = config.profileKey() == selectedProfileKey
            val mainButton = serverListButton(serverListLabel(config), isSelected, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46),
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    selectedProfileKey = config.profileKey()
                    populateConfig(config)
                    updateActiveStatus(config)
                    renderSavedServers()
                    deleteServerButton.isEnabled = true
                    deleteServerButton.alpha = 1.0f
                }
            }

            savedServersListView.addView(mainButton)
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
        activeServerStatusView.text = getString(R.string.editing_server_status, selected.serverName)
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
            if (highlighted) {
                AppTheme.applySecondaryButton(this, palette)
            } else {
                AppTheme.applyOutlineButton(this, palette)
            }
        }
    }

    private fun serverListLabel(config: TransferConfig): String {
        return config.serverName.ifBlank { config.baseUrl }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applyTheme() {
        AppTheme.applyBackground(settingsScrollView, palette)
        AppTheme.applyCard(settingsCardView, palette)
        themeToggleButton.setImageResource(if (palette.isDark) R.drawable.ic_sun else R.drawable.ic_moon)
        AppTheme.applyIconButton(backButton, palette)
        AppTheme.applyIconButton(themeToggleButton, palette)
        AppTheme.applyIconButton(saveButton, palette, palette.success)
        AppTheme.applyIconButton(deleteServerButton, palette, palette.danger)
        AppTheme.applyIconButton(newServerButton, palette, palette.accent)
        listOf(serverNameView, baseUrlView, authTokenView, fingerprintView).forEach {
            AppTheme.applyInput(it, palette)
        }
        AppTheme.applyText(settingsScrollView, palette)
        AppTheme.applySectionLabel(savedServersLabelView, palette)
        AppTheme.applySectionLabel(editorLabelView, palette)
    }
}
