package flare.client.app.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import flare.client.app.R
import flare.client.app.data.SettingsManager
import flare.client.app.data.db.AppDatabase
import flare.client.app.data.repository.ProfileRepository
import flare.client.app.singbox.SingBoxManager
import flare.client.app.ui.MainActivity
import kotlinx.coroutines.*
import java.util.*

class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitorService"
        private const val NOTIF_CHANNEL = "app_monitor"
        private const val NOTIF_ID = 1002
        private const val VPN_PERMISSION_CHANNEL = "trigger_vpn_permission"
        private const val VPN_PERMISSION_NOTIF_ID = 1003
        private const val STARTUP_GRACE_MS = 5_000L
        private const val MONITOR_INTERVAL_MS = 2_000L
        private const val EVENT_LOOKBACK_MS = 60_000L

        const val EXTRA_REQUEST_VPN_PERMISSION = "flare.client.app.extra.REQUEST_VPN_PERMISSION"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private lateinit var settings: SettingsManager
    private lateinit var repository: ProfileRepository
    
    private var vpnStartedByTrigger = false
    private var lastVpnStartAt = 0L
    private var consecutiveInactiveChecks = 0
    private var hasPendingVpnPermissionRequest = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        val db = AppDatabase.getInstance(this)
        repository = ProfileRepository(db.profileDao(), db.subscriptionDao())
        
        startForeground(NOTIF_ID, buildNotification())
        startMonitoring()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(NOTIF_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL,
                        getString(R.string.app_monitor_active),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.app_monitor_active))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                if (!settings.isAppTriggerEnabled) {
                    stopSelf()
                    break
                }

                evaluateTriggerState()
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private suspend fun evaluateTriggerState() {
        val targetApps = settings.splitTunnelingApps
        if (targetApps.isEmpty()) return

        val now = System.currentTimeMillis()
        val foregroundApp = getForegroundApp()
        val isTargetActive = foregroundApp != null && targetApps.contains(foregroundApp)

        if (isTargetActive) {
            consecutiveInactiveChecks = 0
            if (!SingBoxManager.isRunning) {
                Log.i(
                    TAG,
                    "Target app detected. Starting VPN... foreground=$foregroundApp"
                )
                if (startVpn()) {
                    vpnStartedByTrigger = true
                    lastVpnStartAt = now
                }
            }
            return
        }

        if (SingBoxManager.isRunning && vpnStartedByTrigger) {
            val stillInGracePeriod = now - lastVpnStartAt < STARTUP_GRACE_MS

            if (foregroundApp == this.packageName || foregroundApp == "android") {
                return
            }

            if (stillInGracePeriod) {
                Log.d(
                    TAG,
                    "Ignoring trigger stop during startup grace. foreground=$foregroundApp"
                )
                return
            }

            consecutiveInactiveChecks += 1
            val isDesktop = foregroundApp?.let { isHomeApp(it) } ?: true
            if (!isDesktop && consecutiveInactiveChecks < 2) {
                Log.d(
                    TAG,
                    "Waiting for trigger stop confirmation. foreground=$foregroundApp inactiveChecks=$consecutiveInactiveChecks"
                )
                return
            }

            Log.i(
                TAG,
                "Target app is no longer active. Stopping VPN... foreground=$foregroundApp desktop=$isDesktop"
            )
            stopVpn()
            vpnStartedByTrigger = false
            consecutiveInactiveChecks = 0
        }
    }

    private suspend fun startVpn(): Boolean {
        val profile = repository.getSelectedProfile() ?: return false
        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            if (!hasPendingVpnPermissionRequest) {
                hasPendingVpnPermissionRequest = true
                showVpnPermissionNotification()
            }
            Log.w(TAG, "VPN permission is required before trigger can start the tunnel")
            return false
        }

        hasPendingVpnPermissionRequest = false
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(VPN_PERMISSION_NOTIF_ID)

        val intent = Intent(this, FlareVpnService::class.java).apply {
            action = FlareVpnService.ACTION_START
            putExtra(FlareVpnService.EXTRA_CONFIG, profile.configJson)
            putExtra(FlareVpnService.EXTRA_PROFILE_NAME, profile.name)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        delay(1000)
        return true
    }

    private fun showVpnPermissionNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(VPN_PERMISSION_CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    VPN_PERMISSION_CHANNEL,
                    getString(R.string.trigger_vpn_permission_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_REQUEST_VPN_PERMISSION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, VPN_PERMISSION_CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.trigger_vpn_permission_title))
            .setContentText(getString(R.string.trigger_vpn_permission_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(VPN_PERMISSION_NOTIF_ID, notification)
    }

    private fun stopVpn() {
        val intent = Intent(this, FlareVpnService::class.java).apply {
            action = FlareVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - EVENT_LOOKBACK_MS
        
        val events = usm.queryEvents(startTime, endTime)
        val event = android.app.usage.UsageEvents.Event()
        val activePackages = linkedMapOf<String, Long>()
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    activePackages[event.packageName] = event.timeStamp
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                    activePackages.remove(event.packageName)
                }
            }
        }

        val eventForeground = activePackages.maxByOrNull { it.value }?.key
        if (eventForeground != null) return eventForeground

        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    private fun isHomeApp(packageName: String): Boolean {
        val intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        val pm = packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            ).any { it.activityInfo?.packageName == packageName }
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.packageName == packageName }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
