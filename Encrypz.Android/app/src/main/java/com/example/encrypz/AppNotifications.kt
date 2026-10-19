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
    val isError: Boolean,
    val id: Int = 0,
    val isRead: Boolean = false
)

object AppNotifications {
    private const val CHANNEL_ID = "encrypz_status"
    private const val CHANNEL_NAME = "Encrypz status"
    private const val ATTENTION_NOTIFICATION_ID = 0x454e43
    private const val ALERT_INTERVAL_MILLIS = 5 * 60 * 1000L
    private var nextId = 1
    private var loaded = false
    val unreadCount: Int get() = items.count { !it.isRead }
    val items = mutableStateListOf<EncrypzNotification>()

    fun createChannel(context: Context) {
        load(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Transfer failures that need your attention"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val stored = context.getSharedPreferences("transfer_notifications", Context.MODE_PRIVATE).getString("items", null)
            if (stored != null) {
                val json = org.json.JSONArray(String(com.example.encrypz.data.DeviceSecrets.open(stored), Charsets.UTF_8))
                for (i in 0 until json.length()) {
                    val entry = json.getJSONObject(i)
                    if (entry.getBoolean("error")) {
                        items.add(EncrypzNotification(entry.getString("message"), true,
                            entry.getInt("id"), entry.optBoolean("read")))
                    }
                }
                nextId = (items.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
    }

    private fun persist(context: Context) {
        val json = org.json.JSONArray()
        items.forEach { json.put(org.json.JSONObject().put("message", it.message).put("error", it.isError)
            .put("id", it.id).put("read", it.isRead)) }
        context.getSharedPreferences("transfer_notifications", Context.MODE_PRIVATE).edit()
            .putString("items", com.example.encrypz.data.DeviceSecrets.seal(json.toString().toByteArray(Charsets.UTF_8))).apply()
    }

    fun record(context: Context, message: String, isError: Boolean = false): Int {
        load(context)
        // Routine status belongs in the current screen, not the notification inbox.
        if (!isError || message.isBlank()) return -1
        // Keep an unresolved problem once, including across app restarts.
        if (items.any { !it.isRead && it.message == message }) return -1
        val id = nextId++
        items.add(0, EncrypzNotification(message, isError, id))
        if (items.size > 50) items.removeLast()
        persist(context)
        return id
    }

    fun markAsRead(context: Context, id: Int? = null) {
        load(context)
        items.indices.forEach { index ->
            if (id == null || items[index].id == id) {
                items[index] = items[index].copy(isRead = true)
                NotificationManagerCompat.from(context).cancel(items[index].id)
            }
        }
        if (unreadCount == 0) NotificationManagerCompat.from(context).cancel(ATTENTION_NOTIFICATION_ID)
        persist(context)
    }

    /** Only actionable failures alert; all failures share one system notification. */
    fun showTransfer(context: Context, message: String, isError: Boolean = false) {
        if (!isError) return
        val id = record(context, message, isError)
        if (id < 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val preferences = context.getSharedPreferences("transfer_notifications", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAlert = preferences.getLong("last_important_alert", 0L)
        val alert = lastAlert == 0L || now < lastAlert || now - lastAlert >= ALERT_INTERVAL_MILLIS
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (unreadCount > 1) "$unreadCount transfer issues need attention" else "File transfer needs attention")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(ATTENTION_NOTIFICATION_ID, notification)
        if (alert) preferences.edit().putLong("last_important_alert", now).apply()
    }
}
