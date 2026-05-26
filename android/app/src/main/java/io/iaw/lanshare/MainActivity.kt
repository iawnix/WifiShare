package io.iaw.lanshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var receiverNameView: TextView
    private lateinit var receiverStatusView: TextView
    private lateinit var receiverUrlView: TextView
    private lateinit var shareSummaryView: TextView
    private lateinit var statusView: TextView
    private lateinit var serverQuickButtonsView: LinearLayout
    private lateinit var settingsButton: Button
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button

    private lateinit var settingsStore: SettingsStore
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var pendingItems: List<SharedItem> = emptyList()
    private var serverProfiles: List<TransferConfig> = emptyList()
    private var uploadInFlight = false
    @Volatile
    private var queueCheckInFlight = false
    private val uploadCompletionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UploadService.ACTION_UPLOAD_FINISHED) {
                return
            }
            uploadInFlight = false
            val message = intent.getStringExtra(UploadService.EXTRA_RESULT_MESSAGE)
                ?: getString(R.string.upload_complete, pendingItems.size)
            statusView.text = message
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            updateSendButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.applyInsetPadding(findViewById(R.id.mainScroll))

        settingsStore = SettingsStore(this)
        requestNotificationPermissionIfNeeded()
        bindViews()
        registerUploadCompletionReceiver()
        attachListeners()
        refreshReceiverCard()
        handleAppIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) {
            refreshReceiverCard()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uploadCompletionReceiver)
        networkExecutor.shutdownNow()
    }

    private fun bindViews() {
        receiverNameView = findViewById(R.id.receiverNameText)
        receiverStatusView = findViewById(R.id.receiverStatusText)
        receiverUrlView = findViewById(R.id.receiverUrlText)
        shareSummaryView = findViewById(R.id.shareSummary)
        statusView = findViewById(R.id.statusText)
        serverQuickButtonsView = findViewById(R.id.serverQuickButtons)
        settingsButton = findViewById(R.id.settingsButton)
        sendButton = findViewById(R.id.sendButton)
        receiveButton = findViewById(R.id.receiveButton)
    }

    private fun attachListeners() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sendButton.setOnClickListener {
            val config = currentConfig()
            if (config == null) {
                statusView.text = getString(R.string.invalid_config)
                return@setOnClickListener
            }
            if (pendingItems.isEmpty()) {
                statusView.text = getString(R.string.no_pending_share)
                return@setOnClickListener
            }
            uploadInFlight = true
            updateSendButtonState()
            statusView.text = getString(R.string.uploading)
            val serviceIntent = UploadService.createIntent(this, config, pendingItems)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (exc: Exception) {
                uploadInFlight = false
                updateSendButtonState()
                statusView.text = exc.message ?: getString(R.string.upload_failed)
            }
        }

        receiveButton.setOnClickListener {
            receiveQueuedFiles(auto = false)
        }
    }

    private fun handleAppIntent(intent: Intent?) {
        handlePairingIntent(intent)
        if (intent?.action == ACTION_RECEIVE_QUEUE) {
            handleShareIntent(intent, autoReceiveWhenEmpty = false)
            receiveQueuedFiles(auto = false)
            return
        }
        handleShareIntent(intent, autoReceiveWhenEmpty = false)
    }

    private fun handlePairingIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) {
            return false
        }

        val config = PairingConfigParser.fromIntent(intent)
        if (config == null) {
            statusView.text = getString(R.string.invalid_pairing_link)
            return false
        }

        settingsStore.save(config)
        refreshReceiverCard()
        WifiShareWidgetProvider.updateAllWidgets(this)
        val message = getString(R.string.pairing_saved, config.serverName)
        statusView.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }

    private fun handleShareIntent(intent: Intent?, autoReceiveWhenEmpty: Boolean) {
        pendingItems = ShareIntentParser.parse(this, intent)
        if (pendingItems.isEmpty()) {
            shareSummaryView.text = getString(R.string.no_pending_share)
            updateSendButtonState()
            if (autoReceiveWhenEmpty) {
                receiveQueuedFiles(auto = true)
            }
            return
        }

        val summary = buildString {
            append(getString(R.string.pending_share_count, pendingItems.size))
            append("\n")
            pendingItems.take(5).forEach { append("- ").append(it.displayName).append("\n") }
            if (pendingItems.size > 5) {
                append(getString(R.string.pending_share_more, pendingItems.size - 5))
            }
        }.trim()
        shareSummaryView.text = summary
        updateSendButtonState()
    }

    private fun receiveQueuedFiles(auto: Boolean) {
        val config = currentConfig()
        if (config == null) {
            if (!auto) {
                statusView.text = getString(R.string.invalid_config)
            }
            return
        }
        if (queueCheckInFlight) {
            return
        }

        queueCheckInFlight = true
        receiveButton.isEnabled = false
        receiveButton.alpha = 0.55f
        if (!auto) {
            statusView.text = getString(R.string.checking_queue)
        }

        networkExecutor.execute {
            try {
                val count = DownloadClient(this, config).fetchAllPending()
                runOnUiThread {
                    if (count > 0) {
                        val message = getString(R.string.download_complete, count)
                        statusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else if (!auto) {
                        statusView.text = getString(R.string.no_phone_queue)
                    }
                }
            } catch (exc: Exception) {
                runOnUiThread {
                    statusView.text = exc.message ?: getString(R.string.receive_failed)
                }
            } finally {
                runOnUiThread {
                    queueCheckInFlight = false
                    receiveButton.isEnabled = true
                    receiveButton.alpha = 1.0f
                }
            }
        }
    }

    private fun refreshReceiverCard() {
        serverProfiles = settingsStore.loadAll()
        val config = currentConfig()
        refreshQuickSwitchButtons(config)
        if (config == null) {
            receiverNameView.text = getString(R.string.receiver_missing)
            receiverStatusView.text = getString(R.string.receiver_not_paired)
            receiverUrlView.text = getString(R.string.receiver_url_empty)
            return
        }

        receiverNameView.text = config.serverName
        receiverStatusView.text = getString(R.string.receiver_ready)
        receiverUrlView.text = config.baseUrl
    }

    private fun refreshQuickSwitchButtons(activeConfig: TransferConfig?) {
        serverQuickButtonsView.removeAllViews()
        if (serverProfiles.isEmpty()) {
            serverQuickButtonsView.addView(serverButton(getString(R.string.no_saved_servers), false, enabled = false))
            return
        }

        serverProfiles.forEach { config ->
            val isActive = config.profileKey() == activeConfig?.profileKey()
            val button = serverButton(config.serverName.ifBlank { config.baseUrl }, isActive, enabled = true)
            button.setOnClickListener {
                if (isActive) {
                    return@setOnClickListener
                }
                if (settingsStore.setActive(config)) {
                    refreshReceiverCard()
                    WifiShareWidgetProvider.updateAllWidgets(this)
                    statusView.text = getString(R.string.server_switched, config.serverName)
                }
            }
            serverQuickButtonsView.addView(button)
        }
    }

    private fun serverButton(label: String, active: Boolean, enabled: Boolean): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(180)
            minWidth = dp(86)
            isEnabled = enabled
            alpha = if (enabled) 1.0f else 0.55f
            setTextColor(getColor(if (active) android.R.color.white else R.color.lss_teal))
            setBackgroundResource(if (active) R.drawable.button_secondary else R.drawable.button_outline)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun updateSendButtonState() {
        val enabled = pendingItems.isNotEmpty() && !uploadInFlight
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1.0f else 0.55f
    }

    private fun currentConfig(): TransferConfig? {
        return settingsStore.loadActive()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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

    private fun registerUploadCompletionReceiver() {
        val filter = IntentFilter(UploadService.ACTION_UPLOAD_FINISHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uploadCompletionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(uploadCompletionReceiver, filter)
        }
    }

    companion object {
        const val ACTION_RECEIVE_QUEUE = "io.iaw.lanshare.action.RECEIVE_QUEUE"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}
