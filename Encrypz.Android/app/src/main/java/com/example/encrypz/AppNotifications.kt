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
    private var nextId = 1
    private var loaded = false
    private var lastMessageAt = 0L
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
                description = "Important upload and download updates"
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
                    items.add(EncrypzNotification(entry.getString("message"), entry.getBoolean("error"),
                        entry.getInt("id"), entry.optBoolean("read")))
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
        if (message.isBlank()) return -1
        // A transfer may also update the status state; keep one inbox entry for that event.
        val now = android.os.SystemClock.elapsedRealtime()
        items.firstOrNull()?.takeIf { it.message == message && now - lastMessageAt < 2000 }?.let { return it.id }
        lastMessageAt = now
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
        persist(context)
    }

    /** The only system-notification entry point; called exclusively by file transfers. */
    fun showTransfer(context: Context, message: String, isError: Boolean = false) {
        val id = record(context, message, isError)
        if (id < 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isError) "File transfer needs attention" else "Encrypz file transfer")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
