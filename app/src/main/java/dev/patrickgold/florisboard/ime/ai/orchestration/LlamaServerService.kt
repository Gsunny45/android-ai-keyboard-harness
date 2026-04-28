package dev.patrickgold.florisboard.ime.ai.orchestration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.patrickgold.florisboard.ime.ai.CteSettingsActivity
import dev.patrickgold.florisboard.ime.ai.providers.LlamaCppLocal
import dev.patrickgold.florisboard.ime.ai.providers.NativeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors the local llama-server instance.
 *
 * PATH 1 (active): polls `http://127.0.0.1:8080/health` every 5 s while
 *   the IME is active. Posts a sticky notification with server status and
 *   tokens/sec from `/metrics`.
 *
 * PATH 2 (stubs): hooks for a future bundled .so backend — see
 *   [NativeBackend] in LlamaCppLocal.kt.
 *
 * Auto-shutdown:
 *   - The service stops automatically after [IDLE_TIMEOUT_MS] of inactivity
 *     (no AI requests) AND the screen is off.
 *   - Call [resetIdleTimer] whenever an AI request is dispatched.
 *   - A [ScreenStateReceiver] listens for ACTION_SCREEN_OFF to trigger an
 *     immediate shutdown check.
 */
class LlamaServerService : Service() {

    companion object {
        private const val TAG = "LlamaServerService"
        private const val NOTIFICATION_CHANNEL_ID = "llama_server_monitor"
        private const val NOTIFICATION_ID = 0x0BAD0200

        /** Interval between /health polls. */
        private const val POLL_INTERVAL_MS = 5_000L

        /** No-request grace period before auto-shutdown. */
        private const val IDLE_TIMEOUT_MS = 600_000L // 10 min

        /** Base URL for llama-server endpoints. */
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"

        /** Intent action to start monitoring. */
        private const val ACTION_START = "dev.patrickgold.florisboard.ime.ai.action.START_MONITOR"
        /** Intent action to stop monitoring. */
        private const val ACTION_STOP = "dev.patrickgold.florisboard.ime.ai.action.STOP_MONITOR"
        /** Intent action to ping (reset idle timer). */
        private const val ACTION_PING = "dev.patrickgold.florisboard.ime.ai.action.PING"

        /**
         * Start the monitoring service.
         * Safe to call multiple times; the system merges start requests.
         */
        fun start(context: Context) {
            val intent = Intent(context, LlamaServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the monitoring service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, LlamaServerService::class.java))
        }

        /**
         * Reset the idle timer. Call this from the IME whenever an AI
         * request is dispatched so the service doesn't shut down while
         * the user is actively using AI features.
         */
        fun ping(context: Context) {
            val intent = Intent(context, LlamaServerService::class.java).apply {
                action = ACTION_PING
            }
            context.startService(intent)
        }
    }

    // ── Service state ─────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var idleSinceMs: Long? = null // null = active
    private var screenOffReceiver: ScreenStateReceiver? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager

    // Most recent metrics for the notification
    private var lastHealthOk: Boolean = false
    private var lastHealthLatencyMs: Long = 0L
    private var lastTokensPerSec: Float? = null
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()

        // Register screen-off receiver for auto-shutdown
        screenOffReceiver = ScreenStateReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startPolling()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_PING -> {
                idleSinceMs = null // reset idle timer
                // Ensure polling is running
                if (pollJob == null || pollJob?.isActive != true) {
                    startPolling()
                }
            }
            else -> {
                // First start: show foreground notification and begin polling
                startForeground(NOTIFICATION_ID, buildNotification())
                startPolling()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
        screenOffReceiver?.let { unregisterReceiver(it) }
        screenOffReceiver = null
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ── Polling loop ──────────────────────────────────────────────────────

    private fun startPolling() {
        if (pollJob?.isActive == true) return // already polling
        idleSinceMs = null
        pollJob = serviceScope.launch {
            Log.d(TAG, "Polling started (every ${POLL_INTERVAL_MS}ms)")
            while (isActive) {
                pollHealth()
                updateNotification()

                // Auto-shutdown check
                checkAutoShutdown()

                delay(POLL_INTERVAL_MS)
            }
            Log.d(TAG, "Polling stopped")
        }
    }

    private suspend fun pollHealth() {
        // PATH 1: HTTP health check
        val health = LlamaCppLocal.checkHealth(DEFAULT_BASE_URL)
        lastHealthOk = health.success
        lastHealthLatencyMs = health.latencyMs
        lastError = health.error

        if (health.success) {
            // Fetch tokens/sec from /metrics
            lastTokensPerSec = LlamaCppLocal.fetchTokensPerSec(DEFAULT_BASE_URL)
            Log.d(TAG, "Health OK: ${health.latencyMs}ms, t/s=${lastTokensPerSec}")
        } else {
            lastTokensPerSec = null
            Log.w(TAG, "Health FAIL: ${health.error}")
        }
    }

    private fun checkAutoShutdown() {
        // PATH 1: we cannot start Termux, so idle + screen off → shut down
        if (!powerManager.isInteractive) {
            // Screen is off
            if (idleSinceMs == null) {
                idleSinceMs = System.currentTimeMillis()
                Log.d(TAG, "Screen off — idle timer started")
            } else {
                val idleMs = System.currentTimeMillis() - idleSinceMs!!
                if (idleMs >= IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "Auto-shutdown: screen off for ${idleMs}ms (> $IDLE_TIMEOUT_MS ms)")
                    stopSelf()
                }
            }
        } else {
            // Screen is on — alive as long as someone is polling
            idleSinceMs = null
        }
    }

    /**
     * Called externally (e.g. from the IME) when an AI request is dispatched.
     * Resets the idle timer so the service doesn't shut down during active use.
     */
    fun resetIdleTimer() {
        idleSinceMs = null
        Log.d(TAG, "Idle timer reset (AI request dispatched)")
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LLaMA Server Monitor",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows llama-server health and tokens/sec"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openSettingsIntent = Intent(this, CteSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (lastHealthOk) "LLaMA: Online" else "LLaMA: Offline"
        val body = buildString {
            if (lastHealthOk) {
                append("${lastHealthLatencyMs}ms  ·  ")
                if (lastTokensPerSec != null) {
                    append(String.format("%.1f tok/s", lastTokensPerSec))
                } else {
                    append("-- tok/s")
                }
            } else {
                append(lastError ?: "Unreachable — is llama-server running?")
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this).apply {
                setPriority(Notification.PRIORITY_LOW)
            }
        }

        return builder.apply {
            setContentTitle(title)
            setContentText(body)
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setContentIntent(pendingIntent)
            setOngoing(true)
            setShowWhen(false)
        }.build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── Screen state receiver ────────────────────────────────────────────

    /**
     * Listens for screen-off events to trigger the auto-shutdown check.
     * Registered in [onCreate], unregistered in [onDestroy].
     */
    private inner class ScreenStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF — idle timer will count down")
                    // idleSinceMs is set in the next poll cycle via checkAutoShutdown()
                }
            }
        }
    }
}
