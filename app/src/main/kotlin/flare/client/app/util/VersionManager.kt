package flare.client.app.util

import android.content.Context
import android.util.Log
import flare.client.app.BuildConfig
import flare.client.app.R
import flare.client.app.ui.notification.AppNotificationManager
import flare.client.app.ui.notification.NotificationType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object VersionManager {
    private const val TAG = "VersionManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/gitwelk/FlareVPN/tags"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkUpdates(context: Context) {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("User-Agent", "Flare-Android")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to check updates", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "GitHub API returned error: ${response.code}")
                        return
                    }

                    val body = response.body?.string() ?: return
                    val tags = JSONArray(body)
                    if (tags.length() == 0) return

                    val latestTag = tags.getJSONObject(0).getString("name")
                    val latestVersion = latestTag.replace(Regex("[^0-9.]"), "")
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewer(latestVersion, currentVersion)) {
                        val message = context.getString(R.string.update_available_title, latestVersion)
                        
                        AppNotificationManager.showNotification(
                            NotificationType.WARNING,
                            message,
                            15
                        )

                        AppNotificationManager.showSystemNotification(
                            context,
                            "Flare Update",
                            message
                        )
                    }
                }
            }
        })
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until size) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
