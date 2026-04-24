package io.iaw.lanshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var receiverNameView: TextView
    private lateinit var receiverStatusView: TextView
    private lateinit var receiverUrlView: TextView
    private lateinit var shareSummaryView: TextView
    private lateinit var statusView: TextView
    private lateinit var settingsButton: Button
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button

    private lateinit var settingsStore: SettingsStore
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var pendingItems: List<SharedItem> = emptyList()
    @Volatile
    private var queueCheckInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsStore = SettingsStore(this)
        bindViews()
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
        networkExecutor.shutdownNow()
    }

    private fun bindViews() {
        receiverNameView = findViewById(R.id.receiverNameText)
        receiverStatusView = findViewById(R.id.receiverStatusText)
        receiverUrlView = findViewById(R.id.receiverUrlText)
        shareSummaryView = findViewById(R.id.shareSummary)
        statusView = findViewById(R.id.statusText)
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
            sendButton.isEnabled = false
            sendButton.alpha = 0.55f
            statusView.text = getString(R.string.uploading)
            networkExecutor.execute {
                val client = UploadClient(this, config)
                val failures = mutableListOf<String>()
                pendingItems.forEachIndexed { index, item ->
                    try {
                        client.upload(item)
                        runOnUiThread {
                            statusView.text = getString(
                                R.string.upload_progress,
                                index + 1,
                                pendingItems.size,
                                item.displayName,
                            )
                        }
                    } catch (exc: Exception) {
                        failures += "${item.displayName}: ${exc.message}"
                    }
                }
                runOnUiThread {
                    updateSendButtonState()
                    if (failures.isEmpty()) {
                        val message = getString(R.string.upload_complete, pendingItems.size)
                        statusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else {
                        statusView.text = failures.joinToString(separator = "\n")
                    }
                }
            }
        }

        receiveButton.setOnClickListener {
            receiveQueuedFiles(auto = false)
        }
    }

    private fun handleAppIntent(intent: Intent?) {
        val pairedFromLink = handlePairingIntent(intent)
        handleShareIntent(intent, autoReceiveWhenEmpty = !pairedFromLink)
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
        val config = currentConfig()
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

    private fun updateSendButtonState() {
        val enabled = pendingItems.isNotEmpty()
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1.0f else 0.55f
    }

    private fun currentConfig(): TransferConfig? {
        return settingsStore.load()
    }
}
