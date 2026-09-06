package io.iaw.lanshare

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat

class SettingsActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var settingsContent: ViewGroup
    private lateinit var overviewPanel: View
    private lateinit var languagePanel: View
    private lateinit var themePanel: View
    private lateinit var editorPanel: View
    private lateinit var savedServersListView: LinearLayout
    private lateinit var languageRowView: View
    private lateinit var themeRowView: View
    private lateinit var generalSettingsGroup: View
    private lateinit var widgetSettingsGroup: View
    private lateinit var titleView: TextView
    private lateinit var languageValueView: TextView
    private lateinit var themeValueView: TextView
    private lateinit var overviewStatusView: TextView
    private lateinit var editorStatusView: TextView
    private lateinit var savedServersLabelView: TextView
    private lateinit var generalLabelView: TextView
    private lateinit var appearanceLabelView: TextView
    private lateinit var languageOptionsView: RadioGroup
    private lateinit var themeOptionsView: RadioGroup
    private lateinit var themeSystemStatusView: TextView
    private lateinit var serverNameView: EditText
    private lateinit var baseUrlView: EditText
    private lateinit var authTokenView: EditText
    private lateinit var fingerprintView: EditText
    private lateinit var backButton: ImageButton
    private lateinit var actionButton: ImageButton
    private lateinit var languageIconView: ImageView
    private lateinit var themeIconView: ImageView
    private lateinit var saveLocationIconView: ImageView
    private lateinit var widgetAppearanceIconView: ImageView
    private lateinit var deleteServerButton: Button

    private lateinit var settingsStore: SettingsStore
    private lateinit var themeMode: ThemeModeSetting
    private lateinit var palette: ThemePalette
    private var savedProfiles: List<TransferConfig> = emptyList()
    private var selectedProfileId: String? = null
    private var screen = SettingsScreen.OVERVIEW
    private var overviewMessage: CharSequence? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settingsStore = SettingsStore(this)
        themeMode = settingsStore.loadThemeMode()
        AppTheme.applyMode(themeMode)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        palette = AppTheme.palette(this, themeMode)
        AppTheme.applyToActivity(this, palette)
        setContentView(R.layout.activity_settings)
        SystemBars.applyInsetPadding(findViewById(R.id.settingsScroll), includeIme = true)

        bindViews()
        applyTheme()
        attachListeners()
        installImeAwareFocus()

        selectedProfileId = savedInstanceState?.getString(STATE_SELECTED_PROFILE_ID)
        screen = savedInstanceState?.getString(STATE_SCREEN)
            ?.let { runCatching { SettingsScreen.valueOf(it) }.getOrNull() }
            ?: SettingsScreen.OVERVIEW
        val openNewServer = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_NEW_SERVER, false)
        refreshSavedServers()
        if (screen == SettingsScreen.EDITOR) {
            selectedSavedConfig()?.let(::populateConfig)
        }
        renderLanguageOptions()
        renderThemeOptions()
        if (openNewServer) {
            beginNewServer()
        } else {
            showScreen(screen)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::savedServersListView.isInitialized) {
            refreshSavedServers()
            updatePreferenceValues()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PROFILE_ID, selectedProfileId)
        outState.putString(STATE_SCREEN, screen.name)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.settingsScroll)
        settingsContent = findViewById(R.id.settingsContent)
        overviewPanel = findViewById(R.id.settingsOverviewPanel)
        languagePanel = findViewById(R.id.languagePanel)
        themePanel = findViewById(R.id.themePanel)
        editorPanel = findViewById(R.id.editorPanel)
        savedServersListView = findViewById(R.id.savedServersList)
        languageRowView = findViewById(R.id.languageRow)
        themeRowView = findViewById(R.id.themeRow)
        generalSettingsGroup = findViewById(R.id.generalSettingsGroup)
        widgetSettingsGroup = findViewById(R.id.widgetSettingsGroup)
        titleView = findViewById(R.id.settingsTitleText)
        languageValueView = findViewById(R.id.languageValueText)
        themeValueView = findViewById(R.id.themeValueText)
        overviewStatusView = findViewById(R.id.overviewStatusText)
        editorStatusView = findViewById(R.id.editorStatusText)
        savedServersLabelView = findViewById(R.id.savedServersLabel)
        generalLabelView = findViewById(R.id.generalLabel)
        appearanceLabelView = findViewById(R.id.appearanceLabel)
        languageOptionsView = findViewById(R.id.languageOptions)
        themeOptionsView = findViewById(R.id.themeOptions)
        themeSystemStatusView = findViewById(R.id.themeSystemStatus)
        serverNameView = findViewById(R.id.serverNameInput)
        baseUrlView = findViewById(R.id.baseUrlInput)
        authTokenView = findViewById(R.id.authTokenInput)
        fingerprintView = findViewById(R.id.fingerprintInput)
        backButton = findViewById(R.id.backButton)
        actionButton = findViewById(R.id.settingsActionButton)
        languageIconView = findViewById(R.id.languageIcon)
        themeIconView = findViewById(R.id.themeIcon)
        saveLocationIconView = findViewById(R.id.saveLocationIcon)
        widgetAppearanceIconView = findViewById(R.id.widgetAppearanceIcon)
        deleteServerButton = findViewById(R.id.deleteServerButton)
    }

    private fun attachListeners() {
        backButton.setOnClickListener {
            if (screen == SettingsScreen.OVERVIEW) {
                finish()
            } else {
                showScreen(SettingsScreen.OVERVIEW)
            }
        }
        actionButton.setOnClickListener {
            when (screen) {
                SettingsScreen.OVERVIEW -> beginNewServer()
                SettingsScreen.EDITOR -> saveEditor()
                SettingsScreen.LANGUAGE,
                SettingsScreen.THEME,
                -> Unit
            }
        }
        languageRowView.setOnClickListener { showScreen(SettingsScreen.LANGUAGE) }
        themeRowView.setOnClickListener { showScreen(SettingsScreen.THEME) }
        deleteServerButton.setOnClickListener { deleteSelectedServer() }
    }

    private fun showScreen(target: SettingsScreen) {
        if (target != screen) {
            UiMotion.begin(settingsContent)
        }
        screen = target
        overviewPanel.visibility = if (target == SettingsScreen.OVERVIEW) View.VISIBLE else View.GONE
        languagePanel.visibility = if (target == SettingsScreen.LANGUAGE) View.VISIBLE else View.GONE
        themePanel.visibility = if (target == SettingsScreen.THEME) View.VISIBLE else View.GONE
        editorPanel.visibility = if (target == SettingsScreen.EDITOR) View.VISIBLE else View.GONE
        when (target) {
            SettingsScreen.OVERVIEW -> {
                titleView.setText(R.string.settings_title)
                backButton.contentDescription = getString(R.string.back_to_transfer)
                actionButton.visibility = View.VISIBLE
                actionButton.setImageResource(R.drawable.ic_add)
                actionButton.contentDescription = getString(R.string.new_server)
            }
            SettingsScreen.LANGUAGE -> {
                titleView.setText(R.string.settings_section_language)
                backButton.contentDescription = getString(R.string.back_to_settings)
                actionButton.visibility = View.INVISIBLE
                renderLanguageOptions()
            }
            SettingsScreen.THEME -> {
                titleView.setText(R.string.settings_section_appearance)
                backButton.contentDescription = getString(R.string.back_to_settings)
                actionButton.visibility = View.INVISIBLE
                renderThemeOptions()
            }
            SettingsScreen.EDITOR -> {
                titleView.setText(
                    if (selectedProfileId == null) R.string.new_server_title else R.string.settings_section_editor,
                )
                backButton.contentDescription = getString(R.string.back_to_settings)
                actionButton.visibility = View.VISIBLE
                actionButton.setImageResource(R.drawable.ic_check)
                actionButton.contentDescription = getString(R.string.save_config)
                deleteServerButton.visibility = if (selectedProfileId == null) View.GONE else View.VISIBLE
            }
        }
        findViewById<View>(R.id.settingsScroll).scrollTo(0, 0)
    }

    private fun refreshSavedServers() {
        savedProfiles = settingsStore.loadAll()
        if (selectedProfileId != null && savedProfiles.none { it.id == selectedProfileId }) {
            selectedProfileId = null
        }
        renderSavedServers()
        updatePreferenceValues()
    }

    private fun renderSavedServers() {
        savedServersListView.removeAllViews()
        if (savedProfiles.isEmpty()) {
            savedServersListView.addView(
                TextView(this).apply {
                    text = getString(R.string.no_saved_servers)
                    gravity = Gravity.CENTER
                    setTextColor(palette.muted)
                    textSize = 13f
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                },
            )
            return
        }

        val activeId = settingsStore.loadActive()?.id
        savedProfiles.forEachIndexed { index, config ->
            if (index > 0) {
                savedServersListView.addView(
                    View(this).apply { setBackgroundColor(palette.cardStroke) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(64)
                    },
                )
            }
            savedServersListView.addView(serverRow(config, config.id == activeId))
        }
    }

    private fun serverRow(config: TransferConfig, active: Boolean): View {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_desktop)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        val name = TextView(this).apply {
            text = config.serverName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text)
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val address = TextView(this).apply {
            text = endpointLabel(config.baseUrl)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(palette.muted)
            textSize = 12f
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }
        val trailing = ImageView(this).apply {
            setImageResource(if (active) R.drawable.ic_check else R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(if (active) palette.accent else palette.muted)
            contentDescription = if (active) {
                getString(R.string.active_server_status, config.serverName)
            } else {
                null
            }
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(66)
            isClickable = true
            isFocusable = true
            background = GradientDrawableFactory.listRow(palette, active)
            setPadding(dp(14), dp(8), dp(12), dp(8))
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(11)
                marginEnd = dp(10)
            })
            addView(trailing, LinearLayout.LayoutParams(dp(22), dp(22)))
            setOnClickListener {
                selectedProfileId = config.id
                populateConfig(config)
                editorStatusView.setText(R.string.settings_hint)
                showScreen(SettingsScreen.EDITOR)
            }
        }
    }

    private fun renderLanguageOptions() {
        languageOptionsView.setOnCheckedChangeListener(null)
        languageOptionsView.check(
            when (AppLanguageController.current(this)) {
                AppLanguage.SYSTEM -> R.id.languageSystem
                AppLanguage.SIMPLIFIED_CHINESE -> R.id.languageChinese
                AppLanguage.ENGLISH -> R.id.languageEnglish
            },
        )
        languageOptionsView.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.languageChinese -> AppLanguage.SIMPLIFIED_CHINESE
                R.id.languageEnglish -> AppLanguage.ENGLISH
                else -> AppLanguage.SYSTEM
            }
            if (language != AppLanguageController.current(this)) {
                AppLanguageController.apply(language)
                WifiShareWidgetProvider.updateAllWidgets(applicationContext)
            }
        }
    }

    private fun updatePreferenceValues() {
        languageValueView.setText(AppLanguageController.current(this).labelResource)
        themeValueView.text = themeModeLabel(themeMode, includeSystemResult = true)
        themeIconView.setImageResource(
            when (themeMode) {
                ThemeModeSetting.SYSTEM -> R.drawable.ic_theme_system
                ThemeModeSetting.LIGHT -> R.drawable.ic_sun
                ThemeModeSetting.DARK -> R.drawable.ic_moon
            },
        )
        overviewStatusView.visibility = if (overviewMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        overviewStatusView.text = overviewMessage
    }

    private fun beginNewServer() {
        selectedProfileId = null
        clearConfig()
        editorStatusView.setText(R.string.new_server_status)
        showScreen(SettingsScreen.EDITOR)
    }

    private fun saveEditor() {
        val config = currentConfig()
        if (!config.isComplete()) {
            editorStatusView.setText(R.string.invalid_config)
            return
        }
        val saveResult = runCatching {
            settingsStore.saveProfile(config, replaceProfileId = selectedProfileId)
        }
        if (saveResult.isFailure) {
            editorStatusView.setText(R.string.secure_storage_failed)
            return
        }
        if (!saveResult.getOrThrow()) {
            editorStatusView.setText(R.string.invalid_config)
            return
        }
        val saved = settingsStore.loadAll().firstOrNull { it.profileKey() == config.profileKey() }
        selectedProfileId = saved?.id
        val message = getString(R.string.config_saved, config.serverName)
        overviewMessage = message
        WifiShareWidgetProvider.updateAllWidgets(this)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refreshSavedServers()
        showScreen(SettingsScreen.OVERVIEW)
    }

    private fun deleteSelectedServer() {
        val selected = selectedSavedConfig() ?: return
        if (!settingsStore.delete(selected.id)) {
            return
        }
        val message = getString(R.string.server_deleted, selected.serverName)
        overviewMessage = message
        selectedProfileId = null
        WifiShareWidgetProvider.updateAllWidgets(this)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refreshSavedServers()
        showScreen(SettingsScreen.OVERVIEW)
    }

    private fun renderThemeOptions() {
        themeSystemStatusView.text = getString(
            R.string.theme_system_current,
            themeModeLabel(
                if (AppTheme.resolve(ThemeModeSetting.SYSTEM, Resources.getSystem().configuration.uiMode) == ResolvedTheme.DARK) {
                    ThemeModeSetting.DARK
                } else {
                    ThemeModeSetting.LIGHT
                },
                includeSystemResult = false,
            ),
        )
        themeOptionsView.setOnCheckedChangeListener(null)
        themeOptionsView.check(
            when (themeMode) {
                ThemeModeSetting.SYSTEM -> R.id.themeSystem
                ThemeModeSetting.LIGHT -> R.id.themeLight
                ThemeModeSetting.DARK -> R.id.themeDark
            },
        )
        themeOptionsView.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.themeLight -> ThemeModeSetting.LIGHT
                R.id.themeDark -> ThemeModeSetting.DARK
                else -> ThemeModeSetting.SYSTEM
            }
            if (selected != themeMode) {
                themeMode = selected
                settingsStore.saveThemeMode(selected)
                WifiShareWidgetProvider.updateAllWidgets(applicationContext)
                updatePreferenceValues()
                AppTheme.applyMode(selected)
            }
        }
    }

    private fun themeModeLabel(mode: ThemeModeSetting, includeSystemResult: Boolean): String {
        return when (mode) {
            ThemeModeSetting.LIGHT -> getString(R.string.theme_light)
            ThemeModeSetting.DARK -> getString(R.string.theme_dark)
            ThemeModeSetting.SYSTEM -> if (includeSystemResult) {
                getString(
                    R.string.theme_system_with_result,
                    themeModeLabel(
                        if (AppTheme.resolve(mode, Resources.getSystem().configuration.uiMode) == ResolvedTheme.DARK) {
                            ThemeModeSetting.DARK
                        } else {
                            ThemeModeSetting.LIGHT
                        },
                        includeSystemResult = false,
                    ),
                )
            } else {
                getString(R.string.theme_system)
            }
        }
    }

    private fun selectedSavedConfig(): TransferConfig? {
        return savedProfiles.firstOrNull { it.id == selectedProfileId }
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

    private fun currentConfig(): TransferConfig {
        return TransferConfig(
            serverName = serverNameView.text.toString().trim(),
            baseUrl = TransferConfig.normalizeBaseUrl(baseUrlView.text.toString()),
            authToken = TransferConfig.normalizeAuthToken(authTokenView.text.toString()),
            certificateSha256 = TransferConfig.normalizeFingerprint(fingerprintView.text.toString()),
            id = selectedProfileId.orEmpty(),
        )
    }

    private fun installImeAwareFocus() {
        listOf(serverNameView, baseUrlView, authTokenView, fingerprintView).forEach { field ->
            field.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.postDelayed({
                        view.requestRectangleOnScreen(
                            Rect(0, 0, view.width, view.height + dp(96)),
                            true,
                        )
                    }, 180)
                }
            }
        }
    }

    private fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
        val host = uri?.host ?: return baseUrl
        val port = uri.port
        return if (port > 0 && port != 443) "$host:$port" else host
    }

    private fun applyTheme() {
        AppTheme.applyBackground(rootView, palette)
        listOf(savedServersListView, generalSettingsGroup, widgetSettingsGroup, languagePanel, themePanel).forEach {
            AppTheme.applyCard(it, palette)
        }
        languageRowView.background = GradientDrawableFactory.dockAction(palette)
        themeRowView.background = GradientDrawableFactory.dockAction(palette)
        AppTheme.applyBareIconButton(backButton, palette)
        AppTheme.applyBareIconButton(actionButton, palette, palette.accent)
        listOf(serverNameView, baseUrlView, authTokenView, fingerprintView).forEach {
            AppTheme.applyInput(it, palette)
        }
        listOf(languageIconView, themeIconView, saveLocationIconView, widgetAppearanceIconView).forEach {
            it.background = GradientDrawableFactory.iconButton(palette)
            it.imageTintList = ColorStateList.valueOf(palette.accent)
        }
        deleteServerButton.setTextColor(palette.danger)
        TextViewCompat.setCompoundDrawableTintList(
            deleteServerButton,
            ColorStateList.valueOf(palette.danger),
        )
        AppTheme.applyText(rootView, palette)
        listOf(savedServersLabelView, generalLabelView, appearanceLabelView).forEach {
            AppTheme.applySectionLabel(it, palette)
        }
        for (index in 0 until languageOptionsView.childCount) {
            val option = languageOptionsView.getChildAt(index) as? RadioButton ?: continue
            option.setTextColor(palette.text)
            option.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(palette.accent, palette.muted),
            )
        }
        for (index in 0 until themeOptionsView.childCount) {
            val option = themeOptionsView.getChildAt(index) as? RadioButton ?: continue
            option.setTextColor(palette.text)
            option.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(palette.accent, palette.muted),
            )
        }
    }

    private fun dp(value: Int): Int = AppTheme.dp(this, value)

    private enum class SettingsScreen {
        OVERVIEW,
        LANGUAGE,
        THEME,
        EDITOR,
    }

    companion object {
        fun createNewServerIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_NEW_SERVER, true)
        }

        private const val EXTRA_OPEN_NEW_SERVER = "open_new_server"
        private const val STATE_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val STATE_SCREEN = "settings_screen"
    }
}
