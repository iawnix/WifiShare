package io.iaw.lanshare

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class ServerPickerActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var contentView: View
    private lateinit var optionsView: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var manageButton: ImageButton

    private lateinit var settingsStore: SettingsStore
    private lateinit var themeMode: ThemeModeSetting
    private lateinit var palette: ThemePalette
    private var selectedProfileId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        settingsStore = SettingsStore(this)
        themeMode = settingsStore.loadThemeMode()
        AppTheme.applyMode(themeMode)
        super.onCreate(savedInstanceState)

        palette = AppTheme.palette(this, themeMode)
        AppTheme.applyToActivity(this, palette)
        setContentView(R.layout.activity_server_picker)
        SystemBars.applyInsetPadding(findViewById(R.id.serverPickerRoot))
        bindViews()
        selectedProfileId = savedInstanceState?.getString(STATE_SELECTED_PROFILE_ID)
            ?: settingsStore.loadActive()?.id
        applyTheme()
        attachListeners()
        UiMotion.enterFromBottom(contentView, dp(12).toFloat(), 190L)
    }

    override fun onResume() {
        super.onResume()
        val latestMode = settingsStore.loadThemeMode()
        if (latestMode != themeMode) {
            themeMode = latestMode
            AppTheme.applyMode(themeMode)
            palette = AppTheme.palette(this, themeMode)
            AppTheme.applyToActivity(this, palette)
            applyTheme()
        }
        val profiles = settingsStore.loadAll()
        if (selectedProfileId == null || profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = settingsStore.loadActive()?.id ?: profiles.firstOrNull()?.id
        }
        renderServers(profiles)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PROFILE_ID, selectedProfileId)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.serverPickerRoot)
        contentView = findViewById(R.id.serverPickerContent)
        optionsView = findViewById(R.id.serverPickerOptions)
        emptyView = findViewById(R.id.serverPickerEmpty)
        closeButton = findViewById(R.id.serverPickerClose)
        manageButton = findViewById(R.id.serverPickerManage)
    }

    private fun attachListeners() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishPicker()
                }
            },
        )
        closeButton.setOnClickListener { finishPicker() }
        manageButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun renderServers(profiles: List<TransferConfig>) {
        optionsView.removeAllViews()
        emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        optionsView.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        profiles.forEachIndexed { index, profile ->
            if (index > 0) {
                optionsView.addView(
                    View(this).apply { setBackgroundColor(palette.cardStroke) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(59)
                    },
                )
            }
            optionsView.addView(serverRow(profile, profile.id == selectedProfileId))
        }
    }

    private fun serverRow(profile: TransferConfig, selected: Boolean): View {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_desktop)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        val nameView = TextView(this).apply {
            text = profile.serverName.ifBlank { endpointLabel(profile.baseUrl) }
            setTextColor(palette.text)
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val endpointView = TextView(this).apply {
            text = endpointLabel(profile.baseUrl)
            setTextColor(palette.muted)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameView)
            addView(
                endpointView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                },
            )
        }
        val trailing = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(palette.accent)
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            contentDescription = if (selected) getString(R.string.current_device) else null
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(62)
            isClickable = true
            isFocusable = true
            contentDescription = profile.serverName
            background = GradientDrawableFactory.listRow(palette, selected)
            setPadding(dp(12), dp(7), dp(10), dp(7))
            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(
                copy,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(11)
                    marginEnd = dp(8)
                },
            )
            addView(trailing, LinearLayout.LayoutParams(dp(28), dp(28)))
            setOnClickListener {
                commitSelection(profile)
            }
        }
    }

    private fun commitSelection(profile: TransferConfig) {
        val currentId = settingsStore.loadActive()?.id
        if (currentId != profile.id && isAnyTransferActive()) {
            Toast.makeText(this, getString(R.string.server_switch_locked), Toast.LENGTH_SHORT).show()
            return
        }
        if (!settingsStore.setActive(profile)) {
            return
        }
        selectedProfileId = profile.id
        WifiShareWidgetProvider.updateAllWidgets(applicationContext)
        finishPicker()
    }

    private fun isAnyTransferActive(): Boolean {
        return UploadStatusStore(this).load().isActive() || TransferStatusStore(this).load().isActive()
    }

    private fun applyTheme() {
        AppTheme.applyBackground(rootView, palette)
        AppTheme.applyBareIconButton(closeButton, palette)
        AppTheme.applyBareIconButton(manageButton, palette, palette.accent)
        AppTheme.applyCard(optionsView, palette)
        AppTheme.applyCard(emptyView, palette)
        AppTheme.applyText(rootView, palette)
    }

    private fun finishPicker() {
        finish()
        UiMotion.suppressPendingTransition(this)
    }

    private fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
        val host = uri?.host ?: return baseUrl
        val port = uri.port
        return if (port > 0 && port != 443) "$host:$port" else host
    }

    private fun dp(value: Int): Int = AppTheme.dp(this, value)

    companion object {
        private const val STATE_SELECTED_PROFILE_ID = "selected_profile_id"
    }
}
