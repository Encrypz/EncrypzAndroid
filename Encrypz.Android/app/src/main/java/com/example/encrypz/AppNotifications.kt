package com.example.encrypz

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.mutableStateListOf

data class EncrypzNotification(
    val message: String,
    val isError: Boolean
)

object AppNotifications {
    private const val CHANNEL_ID = "encrypz_status"
    private const val CHANNEL_NAME = "Encrypz status"
    private var nextId = 0
    val items = mutableStateListOf<EncrypzNotification>()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Important upload and download updates"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun show(context: Context, message: String, isError: Boolean = false) {
        if (message.isBlank() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        items.add(0, EncrypzNotification(message, isError))
        if (items.size > 50) items.removeLast()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isError) "Encrypz needs your attention" else "Encrypz")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(nextId++, notification)
    }

    fun isTransferStatus(message: String): Boolean {
        val normalized = message.lowercase()
        return listOf("upload", "download", "import", "sync").any(normalized::contains)
    }
}
