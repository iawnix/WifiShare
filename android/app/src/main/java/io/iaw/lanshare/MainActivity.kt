package io.iaw.lanshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.Date
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var homePanel: View
    private lateinit var draftPanel: View
    private lateinit var uploadPanel: View
    private lateinit var homeActions: View
    private lateinit var serverRailScroll: HorizontalScrollView
    private lateinit var serverRailView: LinearLayout
    private lateinit var serverCountView: TextView
    private lateinit var homeContextDot: View
    private lateinit var homeContextStatusView: TextView
    private lateinit var recentTransfersList: View
    private lateinit var recentEmptyState: View
    private lateinit var recentEmptyIcon: ImageView
    private lateinit var recentHistoryItems: LinearLayout
    private lateinit var receiveStatusPanel: View
    private lateinit var receiveStatusIcon: ImageView
    private lateinit var homeStatusView: TextView
    private lateinit var draftStatusView: TextView
    private lateinit var draftDestinationNameView: TextView
    private lateinit var pendingCountView: TextView
    private lateinit var pendingTotalView: TextView
    private lateinit var pendingItemsView: LinearLayout
    private lateinit var uploadScreenTitleView: TextView
    private lateinit var uploadDestinationNameView: TextView
    private lateinit var uploadConnectionView: TextView
    private lateinit var uploadItemNameView: TextView
    private lateinit var uploadItemPositionView: TextView
    private lateinit var uploadPercentView: TextView
    private lateinit var uploadByteDetailView: TextView
    private lateinit var uploadMessageView: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var manageServersButton: ImageButton
    private lateinit var draftDestinationIconView: ImageView
    private lateinit var uploadDestinationIconView: ImageView
    private lateinit var receiveProgressView: ProgressBar
    private lateinit var uploadProgressView: ProgressBar
    private lateinit var selectFilesButton: Button
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button
    private lateinit var clearDraftButton: ImageButton
    private lateinit var changeServerButton: ImageButton
    private lateinit var cancelUploadButton: Button

    private lateinit var settingsStore: SettingsStore
    private lateinit var uploadStatusStore: UploadStatusStore
    private lateinit var themeMode: ThemeModeSetting
    private lateinit var palette: ThemePalette
    private val shareDraft = ShareDraft<SharedItem>()
    private var draftMessage: CharSequence? = null
    private var optimisticUploadStatus: UploadStatus? = null
    private var receiveLaunchPending = false
    private var lastUploadToastKey: String? = null
    private var pairingDialog: AlertDialog? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handlePickedFiles(uris)
    }

    private val uploadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UploadService.ACTION_UPLOAD_STATUS_CHANGED) {
                return
            }
            val announcedStatus = UploadService.statusFromIntent(intent) ?: return
            if (announcedStatus.phase == UploadPhase.BUSY) {
                if (optimisticUploadStatus?.operationId == announcedStatus.operationId) {
                    optimisticUploadStatus = null
                }
                Toast.makeText(this@MainActivity, getString(R.string.upload_busy), Toast.LENGTH_SHORT).show()
                renderCurrentScreen()
                return
            }

            val currentStatus = uploadStatusStore.load()
            if (!UploadStatusMachine.isCurrentEvent(currentStatus, announcedStatus)) {
                return
            }
            if (optimisticUploadStatus?.operationId == currentStatus.operationId) {
                optimisticUploadStatus = null
            }
            renderUploadStatus(currentStatus)
            if (currentStatus.isTerminal()) {
                val toastKey = "${currentStatus.operationId}:${currentStatus.phase}"
                if (toastKey != lastUploadToastKey) {
                    lastUploadToastKey = toastKey
                    Toast.makeText(
                        this@MainActivity,
                        uploadMessageFor(currentStatus),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private val receiveCompletionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ReceiveQueueService.ACTION_RECEIVE_FINISHED) {
                return
            }
            receiveLaunchPending = false
            val status = TransferStatusStore(this@MainActivity).load()
            renderCurrentScreen()
            Toast.makeText(
                this@MainActivity,
                receiveMessageFor(status),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settingsStore = SettingsStore(this)
        themeMode = settingsStore.loadThemeMode()
        AppTheme.applyMode(themeMode)
        super.onCreate(savedInstanceState)
        uploadStatusStore = UploadStatusStore(this)
        palette = AppTheme.palette(this, themeMode)
        AppTheme.applyToActivity(this, palette)
        setContentView(R.layout.activity_main)
        SystemBars.applyInsetPadding(findViewById(R.id.mainRoot))

        bindViews()
        applyTheme()
        attachListeners()
        registerInternalReceiver(uploadStatusReceiver, UploadService.ACTION_UPLOAD_STATUS_CHANGED)
        registerInternalReceiver(receiveCompletionReceiver, ReceiveQueueService.ACTION_RECEIVE_FINISHED)
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            handleAppIntent(intent)
        } else {
            restoreDraft(savedInstanceState)
            if (uploadStatusStore.load().isActive()) {
                shareDraft.clear()
            }
        }
        renderCurrentScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!::settingsStore.isInitialized) {
            return
        }
        val latestMode = settingsStore.loadThemeMode()
        if (latestMode != themeMode) {
            themeMode = latestMode
            AppTheme.applyMode(themeMode)
            palette = AppTheme.palette(this, themeMode)
            AppTheme.applyToActivity(this, palette)
            applyTheme()
        }
        renderCurrentScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppIntent(intent)
        renderCurrentScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList(
            STATE_DRAFT_URIS,
            ArrayList(shareDraft.snapshot().map { it.uri }),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pairingDialog?.dismiss()
        unregisterReceiver(uploadStatusReceiver)
        unregisterReceiver(receiveCompletionReceiver)
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.mainRoot)
        homePanel = findViewById(R.id.homePanel)
        draftPanel = findViewById(R.id.draftPanel)
        uploadPanel = findViewById(R.id.uploadPanel)
        homeActions = findViewById(R.id.homeActions)
        serverRailScroll = findViewById(R.id.serverRailScroll)
        serverRailView = findViewById(R.id.serverRail)
        serverCountView = findViewById(R.id.serverCountPill)
        homeContextDot = findViewById(R.id.homeContextDot)
        homeContextStatusView = findViewById(R.id.homeContextStatus)
        recentTransfersList = findViewById(R.id.recentTransfersList)
        recentEmptyState = findViewById(R.id.recentEmptyState)
        recentEmptyIcon = findViewById(R.id.recentEmptyIcon)
        recentHistoryItems = findViewById(R.id.recentHistoryItems)
        receiveStatusPanel = findViewById(R.id.receiveStatusPanel)
        receiveStatusIcon = findViewById(R.id.receiveStatusIcon)
        homeStatusView = findViewById(R.id.homeStatusText)
        draftStatusView = findViewById(R.id.draftStatusText)
        draftDestinationNameView = findViewById(R.id.draftDestinationName)
        pendingCountView = findViewById(R.id.pendingCountText)
        pendingTotalView = findViewById(R.id.pendingTotalText)
        pendingItemsView = findViewById(R.id.pendingItemsList)
        uploadScreenTitleView = findViewById(R.id.uploadScreenTitle)
        uploadDestinationNameView = findViewById(R.id.uploadDestinationName)
        uploadConnectionView = findViewById(R.id.uploadConnectionText)
        uploadItemNameView = findViewById(R.id.uploadItemName)
        uploadItemPositionView = findViewById(R.id.uploadItemPosition)
        uploadPercentView = findViewById(R.id.uploadPercentText)
        uploadByteDetailView = findViewById(R.id.uploadByteDetail)
        uploadMessageView = findViewById(R.id.uploadMessageText)
        settingsButton = findViewById(R.id.settingsButton)
        manageServersButton = findViewById(R.id.manageServersButton)
        draftDestinationIconView = findViewById(R.id.draftDestinationIcon)
        uploadDestinationIconView = findViewById(R.id.uploadDestinationIcon)
        receiveProgressView = findViewById(R.id.receiveProgress)
        uploadProgressView = findViewById(R.id.uploadProgress)
        selectFilesButton = findViewById(R.id.selectFilesButton)
        sendButton = findViewById(R.id.sendButton)
        receiveButton = findViewById(R.id.receiveButton)
        clearDraftButton = findViewById(R.id.clearDraftButton)
        changeServerButton = findViewById(R.id.changeServerButton)
        cancelUploadButton = findViewById(R.id.cancelUploadButton)
    }

    private fun attachListeners() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        manageServersButton.setOnClickListener { openServerPicker() }
        selectFilesButton.setOnClickListener { chooseFiles() }
        changeServerButton.setOnClickListener { openServerPicker() }
        findViewById<View>(R.id.draftDestination).setOnClickListener { openServerPicker() }
        clearDraftButton.setOnClickListener {
            shareDraft.clear()
            draftMessage = null
            renderHome()
        }
        sendButton.setOnClickListener { sendDraft() }
        receiveButton.setOnClickListener { receiveQueuedFiles() }
        cancelUploadButton.setOnClickListener { stopOrDismissUpload() }
    }

    private fun handleAppIntent(intent: Intent?) {
        if (handlePairingIntent(intent)) {
            return
        }
        if (intent?.action == ACTION_RECEIVE_QUEUE) {
            receiveQueuedFiles()
            return
        }
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        if (optimisticUploadStatus?.isActive() == true || uploadStatusStore.load().isActive()) {
            Toast.makeText(this, getString(R.string.upload_busy), Toast.LENGTH_SHORT).show()
            return
        }
        val items = runCatching { ShareIntentParser.parse(this, intent) }
            .getOrElse {
                draftMessage = getString(R.string.upload_file_unavailable)
                emptyList()
            }
        uploadStatusStore.load().takeIf { it.isTerminal() }?.let {
            uploadStatusStore.dismiss(it.operationId)
        }
        shareDraft.replace(items)
    }

    private fun handlePairingIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) {
            return false
        }
        val config = PairingConfigParser.fromIntent(intent)
        intent.data = null
        if (config == null) {
            Toast.makeText(this, getString(R.string.invalid_pairing_link), Toast.LENGTH_LONG).show()
            return false
        }

        pairingDialog?.dismiss()
        pairingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.pairing_confirm_title)
            .setMessage(
                getString(
                    R.string.pairing_confirm_message,
                    config.serverName,
                    endpointLabel(config.baseUrl),
                    config.certificateSha256.chunked(4).joinToString(" "),
                ),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pairing_confirm_action) { _, _ ->
                runCatching {
                    settingsStore.save(config)
                    WifiShareWidgetProvider.updateAllWidgets(this)
                    renderCurrentScreen()
                }.onSuccess {
                    Toast.makeText(
                        this,
                        getString(R.string.pairing_saved, config.serverName),
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure {
                    Toast.makeText(this, R.string.secure_storage_failed, Toast.LENGTH_LONG).show()
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { pairingDialog = null }
                dialog.show()
            }
        return true
    }

    private fun restoreDraft(state: Bundle) {
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.getParcelableArrayList(STATE_DRAFT_URIS, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            state.getParcelableArrayList<Uri>(STATE_DRAFT_URIS).orEmpty()
        }
        val items = runCatching { ShareIntentParser.fromUris(this, uris) }
            .getOrElse {
                draftMessage = getString(R.string.upload_file_unavailable)
                emptyList()
            }
        shareDraft.replace(items)
    }

    private fun renderCurrentScreen() {
        optimisticUploadStatus?.let {
            renderUploadStatus(it)
            return
        }
        val uploadStatus = uploadStatusStore.load()
        when {
            uploadStatus.isActive() || uploadStatus.isTerminal() -> renderUploadStatus(uploadStatus)
            !shareDraft.isEmpty -> renderDraft()
            else -> renderHome()
        }
    }

    private fun renderHome(animateServerSelection: Boolean = false) {
        showOnly(homePanel)
        val profiles = settingsStore.loadAll()
        val config = settingsStore.loadActive()
        val receiveStatus = TransferStatusStore(this).load()
        if (receiveLaunchPending && receiveStatus.isActive()) {
            receiveLaunchPending = false
        }
        if (config == null) {
            receiveButton.contentDescription = getString(R.string.widget_receive)
            homeContextStatusView.setText(R.string.no_server_selected)
        } else {
            receiveButton.contentDescription = getString(R.string.receive_from_server, config.serverName)
            homeContextStatusView.text = endpointLabel(config.baseUrl)
        }
        homeContextDot.background = GradientDrawableFactory.statusDot(palette, config != null)
        renderServerRail(
            profiles = profiles,
            activeProfileId = config?.id,
            transferActive = isAnyTransferActive(),
            animateSelection = animateServerSelection,
        )
        val showingReceiveStatus = renderReceiveStatus(config, receiveStatus)
        renderTransferHistory(receiveStatus, showingReceiveStatus)
    }

    private fun renderServerRail(
        profiles: List<TransferConfig>,
        activeProfileId: String?,
        transferActive: Boolean,
        animateSelection: Boolean,
    ) {
        serverCountView.text = String.format(Locale.getDefault(), "%d", profiles.size)
        serverCountView.contentDescription = resources.getQuantityString(
            R.plurals.saved_server_count,
            profiles.size,
            profiles.size,
        )
        manageServersButton.alpha = if (transferActive) 0.48f else 1f

        serverRailView.removeAllViews()
        var selectedTile: View? = null
        var addTileInserted = false
        profiles.forEachIndexed { index, profile ->
            if (index == HOME_VISIBLE_SERVER_SLOTS) {
                addServerTileToRail(transferActive)
                addTileInserted = true
            }
            val selected = profile.id == activeProfileId
            val tile = serverTile(profile, selected, transferActive)
            if (selected) {
                selectedTile = tile
            }
            serverRailView.addView(
                tile,
                LinearLayout.LayoutParams(serverTileWidth(), dp(82)).apply {
                    marginEnd = dp(7)
                },
            )
        }
        if (!addTileInserted) {
            addServerTileToRail(transferActive)
        }

        selectedTile?.let { tile ->
            serverRailScroll.post { ensureServerTileVisible(tile, animateSelection) }
        }
    }

    private fun addServerTileToRail(transferActive: Boolean) {
        serverRailView.addView(
            addServerTile(transferActive),
            LinearLayout.LayoutParams(serverTileWidth(), dp(82)).apply {
                marginEnd = dp(7)
            },
        )
    }

    private fun serverTile(
        profile: TransferConfig,
        selected: Boolean,
        transferActive: Boolean,
    ): View {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_desktop)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val marker = View(this).apply {
            background = GradientDrawableFactory.statusDot(palette, selected)
            visibility = if (selected) View.VISIBLE else View.GONE
        }
        val iconHolder = android.widget.FrameLayout(this).apply {
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(38), dp(38)))
            addView(
                marker,
                android.widget.FrameLayout.LayoutParams(dp(9), dp(9), Gravity.END or Gravity.BOTTOM),
            )
        }
        val name = TextView(this).apply {
            text = profile.serverName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            alpha = if (transferActive && !selected) 0.52f else 1f
            contentDescription = getString(
                if (selected) R.string.server_tile_current else R.string.server_tile_switch,
                profile.serverName,
            )
            background = GradientDrawableFactory.serverTile(palette, selected)
            elevation = dp(if (palette.isDark) 2 else 4).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(dp(5), dp(7), dp(5), dp(7))
            addView(iconHolder, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(
                name,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(7)
                },
            )
            setOnClickListener { selectServerFromHome(profile, this) }
        }
    }

    private fun addServerTile(transferActive: Boolean): View {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val label = TextView(this).apply {
            text = getString(R.string.new_server)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            alpha = if (transferActive) 0.52f else 1f
            contentDescription = getString(R.string.new_server_title)
            background = GradientDrawableFactory.serverTile(palette, false)
            elevation = dp(if (palette.isDark) 2 else 4).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(dp(5), dp(7), dp(5), dp(7))
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(7)
                },
            )
            setOnClickListener { openNewServerFromHome() }
        }
    }

    private fun selectServerFromHome(profile: TransferConfig, source: View) {
        when (
            HomeServerSelectionPolicy.decide(
                activeProfileId = settingsStore.loadActive()?.id,
                requestedProfileId = profile.id,
                transferActive = isAnyTransferActive(),
            )
        ) {
            HomeServerSelectionDecision.ALREADY_ACTIVE -> ensureServerTileVisible(source, animate = true)
            HomeServerSelectionDecision.BLOCKED_BY_TRANSFER -> showServerSwitchLocked()
            HomeServerSelectionDecision.SELECT -> {
                if (!settingsStore.setActive(profile)) {
                    return
                }
                source.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                WifiShareWidgetProvider.updateAllWidgets(applicationContext)
                renderHome(animateServerSelection = true)
            }
        }
    }

    private fun ensureServerTileVisible(tile: View, animate: Boolean) {
        if (serverRailScroll.width <= 0) {
            return
        }
        val bounds = Rect(0, 0, tile.width, tile.height)
        serverRailView.offsetDescendantRectToMyCoords(tile, bounds)
        val inset = dp(8)
        val visibleLeft = serverRailScroll.scrollX
        val visibleRight = visibleLeft + serverRailScroll.width
        val target = when {
            bounds.left - inset < visibleLeft -> bounds.left - inset
            bounds.right + inset > visibleRight -> bounds.right + inset - serverRailScroll.width
            else -> visibleLeft
        }.coerceIn(0, (serverRailView.width - serverRailScroll.width).coerceAtLeast(0))
        if (target == visibleLeft) {
            return
        }
        if (animate) {
            serverRailScroll.smoothScrollTo(target, 0)
        } else {
            serverRailScroll.scrollTo(target, 0)
        }
    }

    private fun openNewServerFromHome() {
        if (isAnyTransferActive()) {
            showServerSwitchLocked()
            return
        }
        startActivity(SettingsActivity.createNewServerIntent(this))
    }

    private fun showServerSwitchLocked() {
        Toast.makeText(this, getString(R.string.server_switch_locked), Toast.LENGTH_SHORT).show()
    }

    private fun isAnyTransferActive(): Boolean {
        return optimisticUploadStatus?.isActive() == true ||
            uploadStatusStore.load().isActive() ||
            receiveLaunchPending ||
            TransferStatusStore(this).load().isActive()
    }

    private fun renderReceiveStatus(config: TransferConfig?, status: TransferStatus): Boolean {
        val receiving = receiveLaunchPending || status.isActive()
        receiveButton.isEnabled = config != null && !receiving
        receiveButton.alpha = if (receiveButton.isEnabled) 1f else 0.48f
        receiveButton.text = when {
            receiveLaunchPending && !status.isActive() -> getString(R.string.widget_checking)
            receiving -> getString(R.string.widget_receiving)
            else -> getString(R.string.receive_now)
        }
        receiveProgressView.visibility = if (receiving) View.VISIBLE else View.GONE
        receiveProgressView.isIndeterminate = receiveLaunchPending ||
            status.phase != TransferPhase.RECEIVING ||
            status.totalBytes <= 0L
        if (!receiveProgressView.isIndeterminate && status.totalBytes > 0L) {
            receiveProgressView.max = PROGRESS_MAX
            receiveProgressView.progress = progressValue(status.bytesReceived, status.totalBytes)
        }

        val showMessage = receiveLaunchPending ||
            (status.phase != TransferPhase.IDLE && status.phase != TransferPhase.UNCONFIGURED)
        if ((receiveStatusPanel.visibility == View.VISIBLE) != showMessage) {
            UiMotion.begin(homePanel as ViewGroup)
        }
        receiveStatusPanel.visibility = if (showMessage) View.VISIBLE else View.GONE
        homeStatusView.visibility = if (showMessage) View.VISIBLE else View.GONE
        if (showMessage) {
            homeStatusView.text = if (receiveLaunchPending && !status.isActive()) {
                getString(R.string.checking_queue)
            } else {
                receiveMessageFor(status)
            }
        }
        return showMessage
    }

    private fun renderTransferHistory(currentReceiveStatus: TransferStatus, showingReceiveStatus: Boolean) {
        val entries = TransferHistoryStore(this).load(HOME_HISTORY_LIMIT)
            .filterNot { showingReceiveStatus && it.operationId == currentReceiveStatus.operationId }
            .take(HOME_HISTORY_LIMIT)
        recentHistoryItems.removeAllViews()
        entries.forEachIndexed { index, entry ->
            if (index > 0 || showingReceiveStatus) {
                recentHistoryItems.addView(
                    View(this).apply { setBackgroundColor(palette.cardStroke) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(59)
                    },
                )
            }
            recentHistoryItems.addView(historyRow(entry))
        }
        recentEmptyState.visibility = if (!showingReceiveStatus && entries.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun historyRow(entry: TransferHistoryEntry): View {
        val tint = if (entry.result == TransferHistoryResult.ERROR || entry.result == TransferHistoryResult.INTERRUPTED) {
            palette.danger
        } else {
            palette.accent
        }
        val icon = ImageView(this).apply {
            setImageResource(
                if (entry.direction == TransferDirection.SEND) R.drawable.ic_upload else R.drawable.ic_download,
            )
            imageTintList = ColorStateList.valueOf(tint)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        val title = TextView(this).apply {
            text = historyTitle(entry)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text)
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val time = DateFormat.getTimeFormat(this).format(Date(entry.completedAtMillis))
        val server = entry.serverName.ifBlank {
            settingsStore.findById(entry.serverId)?.serverName ?: getString(R.string.unknown_server)
        }
        val detail = TextView(this).apply {
            text = getString(R.string.transfer_history_detail, server, time)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.muted)
            textSize = 11f
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(
                detail,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                },
            )
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(13), dp(8), dp(13), dp(8))
            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(
                copy,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(11)
                },
            )
        }
    }

    private fun historyTitle(entry: TransferHistoryEntry): String {
        return when (entry.direction) {
            TransferDirection.SEND -> when (entry.result) {
                TransferHistoryResult.SUCCESS -> resources.getQuantityString(
                    R.plurals.upload_complete,
                    entry.completedItems,
                    entry.completedItems,
                )
                TransferHistoryResult.CANCELLED -> getString(R.string.upload_stopped_title)
                TransferHistoryResult.INTERRUPTED -> getString(R.string.upload_interrupted)
                TransferHistoryResult.ERROR -> getString(R.string.upload_failed_title)
            }
            TransferDirection.RECEIVE -> when (entry.result) {
                TransferHistoryResult.SUCCESS -> resources.getQuantityString(
                    R.plurals.download_complete,
                    entry.completedItems,
                    entry.completedItems,
                )
                TransferHistoryResult.INTERRUPTED -> getString(R.string.receive_interrupted)
                TransferHistoryResult.ERROR -> getString(R.string.receive_failed)
                TransferHistoryResult.CANCELLED -> getString(R.string.receive_failed)
            }
        }
    }

    private fun chooseFiles() {
        if (optimisticUploadStatus?.isActive() == true || uploadStatusStore.load().isActive()) {
            Toast.makeText(this, getString(R.string.upload_busy), Toast.LENGTH_SHORT).show()
            return
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun handlePickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        if (optimisticUploadStatus?.isActive() == true || uploadStatusStore.load().isActive()) {
            Toast.makeText(this, getString(R.string.upload_busy), Toast.LENGTH_SHORT).show()
            return
        }
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val items = runCatching { ShareIntentParser.fromUris(this, uris) }
            .getOrElse {
                draftMessage = getString(R.string.upload_file_unavailable)
                emptyList()
            }
        if (items.isEmpty()) {
            showHomeMessage(draftMessage ?: getString(R.string.upload_file_unavailable))
            return
        }
        uploadStatusStore.load().takeIf { it.isTerminal() }?.let {
            uploadStatusStore.dismiss(it.operationId)
        }
        draftMessage = null
        shareDraft.replace(items)
        renderDraft()
    }

    private fun renderDraft() {
        showOnly(draftPanel)
        val items = shareDraft.snapshot()
        val config = settingsStore.loadActive()
        draftDestinationNameView.text = config?.serverName ?: getString(R.string.receiver_missing)
        pendingCountView.text = resources.getQuantityString(
            R.plurals.pending_share_count,
            items.size,
            items.size,
        )
        pendingTotalView.text = totalSizeLabel(items)
        renderPendingItems(items)

        val enabled = items.isNotEmpty() && config?.isComplete() == true
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.48f
        sendButton.text = if (items.isEmpty() || config == null) {
            getString(R.string.send_now)
        } else {
            resources.getQuantityString(
                R.plurals.send_items_compact,
                items.size,
                items.size,
            )
        }
        draftStatusView.visibility = if (draftMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        draftStatusView.text = draftMessage
    }

    private fun renderPendingItems(items: List<SharedItem>) {
        pendingItemsView.removeAllViews()
        if (items.isEmpty()) {
            pendingItemsView.addView(
                TextView(this).apply {
                    text = getString(R.string.pending_files_empty)
                    gravity = Gravity.CENTER
                    setTextColor(palette.muted)
                    textSize = 13f
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                },
            )
            return
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                pendingItemsView.addView(
                    View(this).apply { setBackgroundColor(palette.cardStroke) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    ).apply {
                        marginStart = dp(64)
                    },
                )
            }
            pendingItemsView.addView(pendingItemRow(item, index))
        }
    }

    private fun pendingItemRow(item: SharedItem, index: Int): View {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_file)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawableFactory.iconButton(palette)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        val name = TextView(this).apply {
            text = item.displayName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(palette.text)
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        val type = TextView(this).apply {
            text = item.mimeType ?: getString(R.string.pending_item_file)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.muted)
            textSize = 11f
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(type, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }
        val size = TextView(this).apply {
            text = item.sizeBytes?.let { Formatter.formatShortFileSize(this@MainActivity, it) }
                ?: getString(R.string.pending_item_unknown_size)
            maxLines = 1
            setTextColor(palette.muted)
            textSize = 11f
        }
        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(palette.muted)
            background = null
            contentDescription = getString(R.string.remove_pending_item, item.displayName)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener {
                shareDraft.removeAt(index)
                draftMessage = null
                if (shareDraft.isEmpty) {
                    renderHome()
                } else {
                    renderDraft()
                }
            }
        }

        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(8), dp(4), dp(8))
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(11)
                marginEnd = dp(8)
            })
            addView(size, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(remove, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun renderUploadStatus(status: UploadStatus) {
        if (status.phase == UploadPhase.IDLE || status.phase == UploadPhase.BUSY) {
            renderHome()
            return
        }
        showOnly(uploadPanel)
        uploadDestinationNameView.text = status.serverName.ifBlank {
            settingsStore.findById(status.serverId)?.serverName ?: getString(R.string.receiver_missing)
        }
        uploadScreenTitleView.text = when (status.phase) {
            UploadPhase.PREFLIGHT -> getString(R.string.upload_preparing)
            UploadPhase.UPLOADING -> getString(R.string.upload_title)
            UploadPhase.CANCEL_REQUESTED -> getString(R.string.stopping_upload)
            UploadPhase.SUCCESS -> getString(R.string.upload_done_title)
            UploadPhase.CANCELLED -> getString(R.string.upload_stopped_title)
            else -> getString(R.string.upload_failed_title)
        }
        uploadItemNameView.text = status.currentItemName.ifBlank {
            getString(R.string.upload_current_file_unknown)
        }
        uploadItemPositionView.text = if (status.currentItemIndex > 0 && status.totalItems > 0) {
            getString(R.string.upload_item_position, status.currentItemIndex, status.totalItems)
        } else {
            ""
        }

        val terminal = status.isTerminal()
        val hasByteProgress = status.phase == UploadPhase.UPLOADING && status.totalBytes > 0L
        uploadProgressView.isIndeterminate = status.phase == UploadPhase.PREFLIGHT ||
            (status.phase == UploadPhase.UPLOADING && !hasByteProgress) ||
            status.phase == UploadPhase.CANCEL_REQUESTED
        val progress = when {
            hasByteProgress -> progressValue(status.bytesSent, status.totalBytes)
            status.phase == UploadPhase.SUCCESS -> PROGRESS_MAX
            terminal && status.totalItems > 0 ->
                ((status.completedItems.toLong() * PROGRESS_MAX) / status.totalItems).toInt()
            else -> 0
        }
        if (!uploadProgressView.isIndeterminate) {
            uploadProgressView.max = PROGRESS_MAX
            uploadProgressView.progress = progress
        }
        uploadPercentView.text = if (uploadProgressView.isIndeterminate) {
            ""
        } else {
            "${(progress * 100) / PROGRESS_MAX}%"
        }
        uploadByteDetailView.text = when {
            hasByteProgress -> getString(
                R.string.widget_byte_progress,
                Formatter.formatShortFileSize(this, status.bytesSent),
                Formatter.formatShortFileSize(this, status.totalBytes),
            )
            status.phase == UploadPhase.CANCELLED && status.completedItems > 0 -> getString(
                R.string.upload_completed_files_retained,
                status.serverName,
            )
            status.phase == UploadPhase.CANCELLED -> getString(R.string.upload_no_files_completed)
            terminal -> uploadMessageFor(status)
            else -> getString(R.string.upload_preparing)
        }
        uploadConnectionView.visibility = if (terminal) View.INVISIBLE else View.VISIBLE

        uploadMessageView.visibility = if (terminal) View.VISIBLE else View.GONE
        if (terminal) {
            uploadMessageView.text = uploadMessageFor(status)
        }
        cancelUploadButton.isEnabled = status.phase != UploadPhase.CANCEL_REQUESTED
        cancelUploadButton.alpha = if (cancelUploadButton.isEnabled) 1f else 0.48f
        if (terminal) {
            cancelUploadButton.text = getString(R.string.done)
            AppTheme.applyOutlineButton(cancelUploadButton, palette)
        } else {
            cancelUploadButton.text = getString(
                if (status.phase == UploadPhase.CANCEL_REQUESTED) {
                    R.string.stopping_upload
                } else {
                    R.string.stop_sending
                },
            )
            AppTheme.applyOutlineButton(cancelUploadButton, palette, palette.danger)
        }
    }

    private fun sendDraft() {
        val config = settingsStore.loadActive()
        if (config == null || !config.isComplete()) {
            draftMessage = getString(R.string.invalid_config)
            renderDraft()
            return
        }
        val active = uploadStatusStore.load()
        if (active.isActive()) {
            draftMessage = getString(R.string.upload_busy)
            renderDraft()
            return
        }
        val items = shareDraft.snapshot()
        if (items.isEmpty()) {
            draftMessage = getString(R.string.no_pending_share)
            renderDraft()
            return
        }

        val operationId = UUID.randomUUID().toString()
        val optimistic = UploadStatusMachine.begin(
            operationId = operationId,
            serverId = config.id,
            serverName = config.serverName,
            totalItems = items.size,
            now = System.currentTimeMillis(),
        )
        try {
            startForegroundService(UploadService.createIntent(this, operationId, config, items))
            shareDraft.clear()
            draftMessage = null
            optimisticUploadStatus = optimistic
            renderUploadStatus(optimistic)
        } catch (_: Exception) {
            draftMessage = getString(R.string.upload_failed)
            renderDraft()
        }
    }

    private fun stopOrDismissUpload() {
        val status = optimisticUploadStatus ?: uploadStatusStore.load()
        if (status.isTerminal()) {
            uploadStatusStore.dismiss(status.operationId)
            optimisticUploadStatus = null
            renderCurrentScreen()
            return
        }
        if (!status.isActive() || status.phase == UploadPhase.CANCEL_REQUESTED) {
            return
        }
        try {
            startService(UploadService.createCancelIntent(this, status.operationId))
            val cancelling = UploadStatusMachine.requestCancel(status, System.currentTimeMillis())
            optimisticUploadStatus = cancelling
            renderUploadStatus(cancelling)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.upload_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun receiveQueuedFiles() {
        val config = settingsStore.loadActive()
        if (config == null) {
            showHomeMessage(getString(R.string.invalid_config))
            return
        }
        if (receiveLaunchPending || TransferStatusStore(this).load().isActive()) {
            showHomeMessage(getString(R.string.receive_busy))
            return
        }
        receiveLaunchPending = true
        receiveButton.isEnabled = false
        receiveButton.alpha = 0.48f
        receiveButton.text = getString(R.string.widget_checking)
        receiveProgressView.visibility = View.VISIBLE
        showHomeMessage(getString(R.string.checking_queue))
        try {
            startForegroundService(ReceiveQueueService.createIntent(this))
            renderHome()
        } catch (_: Exception) {
            receiveLaunchPending = false
            showHomeMessage(getString(R.string.receive_failed))
            receiveButton.isEnabled = true
            receiveButton.alpha = 1f
            receiveButton.text = getString(R.string.receive_now)
            receiveProgressView.visibility = View.GONE
        }
    }

    private fun openServerPicker() {
        if (isAnyTransferActive()) {
            showServerSwitchLocked()
            return
        }
        startActivity(Intent(this, ServerPickerActivity::class.java))
        UiMotion.suppressPendingTransition(this)
    }

    private fun showHomeMessage(message: CharSequence) {
        showOnly(homePanel)
        UiMotion.begin(homePanel as ViewGroup)
        homeStatusView.text = message
        receiveStatusPanel.visibility = View.VISIBLE
        recentEmptyState.visibility = View.GONE
        homeStatusView.visibility = View.VISIBLE
        renderTransferHistory(TransferStatus(), showingReceiveStatus = true)
    }

    private fun showOnly(panel: View) {
        val changing = panel.visibility != View.VISIBLE
        if (changing) {
            UiMotion.begin(rootView as ViewGroup, 180L)
        }
        homePanel.visibility = if (panel === homePanel) View.VISIBLE else View.GONE
        draftPanel.visibility = if (panel === draftPanel) View.VISIBLE else View.GONE
        uploadPanel.visibility = if (panel === uploadPanel) View.VISIBLE else View.GONE
        homeActions.visibility = if (panel === homePanel) View.VISIBLE else View.GONE
        if (changing) {
            findViewById<ScrollView>(R.id.mainScroll).scrollTo(0, 0)
        }
    }

    private fun uploadMessageFor(status: UploadStatus): String {
        return when (status.phase) {
            UploadPhase.SUCCESS -> resources.getQuantityString(
                R.plurals.upload_complete,
                status.completedItems,
                status.completedItems,
            )
            UploadPhase.CANCELLED -> resources.getQuantityString(
                R.plurals.upload_cancelled,
                status.totalItems,
                status.completedItems,
                status.totalItems,
            )
            UploadPhase.BUSY -> getString(R.string.upload_busy)
            UploadPhase.INTERRUPTED -> getString(R.string.upload_interrupted)
            UploadPhase.ERROR -> {
                if (status.completedItems > 0) {
                    resources.getQuantityString(
                        R.plurals.upload_partial_failure,
                        status.totalItems,
                        status.completedItems,
                        status.totalItems,
                    )
                } else {
                    when (status.errorCode) {
                        UploadErrorCode.INVALID_CONFIG -> getString(R.string.invalid_config)
                        UploadErrorCode.NO_ITEMS -> getString(R.string.no_pending_share)
                        UploadErrorCode.FILE_UNAVAILABLE -> getString(R.string.upload_file_unavailable)
                        UploadErrorCode.NETWORK_UNREACHABLE -> getString(R.string.upload_network_unreachable)
                        UploadErrorCode.TLS_MISMATCH -> getString(R.string.upload_tls_mismatch)
                        UploadErrorCode.AUTH_FAILED -> getString(R.string.upload_auth_failed)
                        UploadErrorCode.FILE_TOO_LARGE -> getString(R.string.upload_file_too_large)
                        UploadErrorCode.SERVER_STORAGE_FULL ->
                            getString(R.string.upload_server_storage_full)
                        UploadErrorCode.SERVER_REJECTED -> getString(R.string.upload_server_rejected)
                        else -> getString(R.string.upload_failed)
                    }
                }
            }
            else -> getString(R.string.uploading)
        }
    }

    private fun receiveMessageFor(status: TransferStatus): String {
        return when (status.phase) {
            TransferPhase.CHECKING -> getString(R.string.checking_queue)
            TransferPhase.RECEIVING -> getString(
                R.string.receive_notification_progress,
                status.currentItemIndex,
                status.currentItemName,
            )
            TransferPhase.SUCCESS -> resources.getQuantityString(
                R.plurals.download_complete,
                status.completedItems,
                status.completedItems,
            )
            TransferPhase.EMPTY -> getString(R.string.no_phone_queue)
            TransferPhase.INTERRUPTED -> getString(R.string.receive_interrupted)
            TransferPhase.BUSY -> getString(R.string.receive_busy)
            TransferPhase.ERROR -> when (status.errorCode) {
                TransferErrorCode.NO_CONFIG -> getString(R.string.invalid_config)
                TransferErrorCode.NETWORK_UNREACHABLE -> getString(R.string.receive_network_unreachable)
                TransferErrorCode.TLS_MISMATCH -> getString(R.string.receive_tls_mismatch)
                TransferErrorCode.AUTH_FAILED -> getString(R.string.receive_auth_failed)
                TransferErrorCode.STORAGE_FAILED -> getString(R.string.receive_storage_failed)
                TransferErrorCode.PARTIAL_FAILURE -> resources.getQuantityString(
                    R.plurals.receive_partial_failure,
                    status.completedItems,
                    status.completedItems,
                )
                else -> getString(R.string.receive_failed)
            }
            else -> getString(R.string.status_idle)
        }
    }

    private fun totalSizeLabel(items: List<SharedItem>): String {
        if (items.isEmpty()) {
            return ""
        }
        val sizes = items.map { it.sizeBytes }
        if (sizes.any { it == null }) {
            return getString(R.string.pending_item_unknown_size)
        }
        return Formatter.formatShortFileSize(this, sizes.filterNotNull().sum())
    }

    private fun progressValue(value: Long, total: Long): Int {
        if (total <= 0L) {
            return 0
        }
        return ((value.coerceIn(0L, total) * PROGRESS_MAX) / total).toInt()
    }

    private fun applyTheme() {
        AppTheme.applyBackground(rootView, palette)
        AppTheme.applyCard(recentTransfersList, palette)
        AppTheme.applyCard(pendingItemsView, palette)
        AppTheme.applyCard(findViewById(R.id.uploadProgressCard), palette)
        AppTheme.applyPill(serverCountView, palette)
        AppTheme.applyBareIconButton(settingsButton, palette)
        AppTheme.applyBareIconButton(manageServersButton, palette, palette.accent)
        AppTheme.applyBareIconButton(clearDraftButton, palette)
        AppTheme.applyBareIconButton(changeServerButton, palette, palette.accent)
        listOf(
            draftDestinationIconView,
            uploadDestinationIconView,
            recentEmptyIcon,
            receiveStatusIcon,
            findViewById<ImageView>(R.id.uploadFileIcon),
        ).forEach {
            it.background = GradientDrawableFactory.iconButton(palette)
            it.imageTintList = ColorStateList.valueOf(palette.accent)
        }
        findViewById<View>(R.id.draftDestination).background = GradientDrawableFactory.selectionSurface(palette)
        AppTheme.applyGlassDock(homeActions, palette)
        AppTheme.applyDockActionButton(selectFilesButton, palette)
        AppTheme.applyDockActionButton(receiveButton, palette)
        AppTheme.applyPrimaryButton(sendButton, palette)
        AppTheme.applyOutlineButton(cancelUploadButton, palette, palette.danger)
        AppTheme.applyProgress(receiveProgressView, palette)
        AppTheme.applyProgress(uploadProgressView, palette)
        AppTheme.applyText(rootView, palette)
    }

    private fun dp(value: Int): Int = AppTheme.dp(this, value)

    private fun serverTileWidth(): Int {
        val available = serverRailScroll.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(36))
        return ((available - dp(21)) / 4).coerceIn(dp(72), dp(96))
    }

    private fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
        val host = uri?.host ?: return getString(R.string.local_network)
        val port = uri.port
        return if (port > 0 && port != 443) "$host:$port" else host
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerInternalReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter, InternalBroadcasts.PERMISSION, null)
        }
    }

    companion object {
        const val ACTION_RECEIVE_QUEUE = "io.iaw.lanshare.action.RECEIVE_QUEUE"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val STATE_DRAFT_URIS = "draft_uris"
        private const val PROGRESS_MAX = 1_000
        private const val HOME_HISTORY_LIMIT = 3
        private const val HOME_VISIBLE_SERVER_SLOTS = 3
    }
}
