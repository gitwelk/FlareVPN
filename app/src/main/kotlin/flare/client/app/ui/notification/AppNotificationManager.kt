package flare.client.app.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import flare.client.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class NotificationType {
    SUCCESS, ERROR, WARNING
}

data class NotificationData(
    val type: NotificationType,
    val text: String,
    val durationSec: Int,
    val actionText: String? = null,
    val onAction: (() -> Unit)? = null
)

object AppNotificationManager {
    private val _notifications = MutableSharedFlow<NotificationData>(extraBufferCapacity = 3)
    val notifications: SharedFlow<NotificationData> = _notifications.asSharedFlow()

    private const val BEST_PROFILE_CHANNEL = "best_profile_updates"
    private const val BEST_PROFILE_NOTIF_ID = 1002

    fun showNotification(type: NotificationType, text: String, durationSec: Int, actionText: String? = null, onAction: (() -> Unit)? = null) {
        _notifications.tryEmit(NotificationData(type, text, durationSec, actionText, onAction))
    }

    fun showSystemNotification(context: Context, title: String, text: String, isHighPriority: Boolean = false) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channelId = if (isHighPriority) "adaptive_tunnel_updates" else BEST_PROFILE_CHANNEL
        val notifId = if (isHighPriority) 1003 else BEST_PROFILE_NOTIF_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(channelId) == null) {
                val importance = if (isHighPriority) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(
                    channelId,
                    if (isHighPriority) "Adaptive Tunnel Updates" else "Profile Updates",
                    importance
                )
                if (isHighPriority) {
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(0, 250)
                }
                manager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setAutoCancel(true)
            .apply {
                if (isHighPriority) {
                    setPriority(NotificationCompat.PRIORITY_HIGH)
                    setVibrate(longArrayOf(0, 250))
                }
            }
            .build()
            
        manager.notify(notifId, notification)
    }
}

