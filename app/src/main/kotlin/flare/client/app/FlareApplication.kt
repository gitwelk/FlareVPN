package flare.client.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import flare.client.app.data.SettingsManager

class FlareApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val settings = SettingsManager(this)
        val mode = when (settings.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        
        if (settings.isAppTriggerEnabled) {
            val intent = android.content.Intent(this, flare.client.app.service.AppMonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
