package com.example.encrypz.ui.vault

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun parseVaultDate(value: String, pattern: String = "MMM d, yyyy"): Long? =
    runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)?.time }
        .getOrNull()?.takeIf { it > 0 }

internal val VaultFileEntry.sortDate: Long
    get() = createdAtMillis ?: parseVaultDate(uploadedAt) ?: 0L

internal val VaultFileEntry.creationDateLabel: String
    get() = if (sortDate > 0) SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(sortDate)) else "Unknown date"

/** Search the same local calendar date shown on file cards, including legacy upload dates. */
internal fun VaultFileEntry.matchesVaultSearch(query: String): Boolean {
    val term = query.trim()
    if (term.isEmpty() || cleanFileName.contains(term, ignoreCase = true)) return true
    if (sortDate <= 0) return false
    val date = Date(sortDate)
    val normalizedTerm = term.replace(",", "").replace(Regex("\\s+"), " ")
    return listOf("yyyy-MM-dd", "MMM d yyyy", "MMMM d yyyy", "MMM yyyy", "MMMM yyyy")
        .any { pattern -> SimpleDateFormat(pattern, Locale.US).format(date)
            .contains(normalizedTerm, ignoreCase = true) }
}

internal fun embeddedCreationDate(bytes: ByteArray, context: Context?, name: String): Long? {
    val photoDate = runCatching {
        val exif = ExifInterface(bytes.inputStream())
        sequenceOf("DateTimeOriginal", "DateTimeDigitized", "DateTime")
            .mapNotNull { exif.getAttribute(it)?.let { value -> parseVaultDate(value, "yyyy:MM:dd HH:mm:ss") } }
            .firstOrNull()
    }.getOrNull()
    if (photoDate != null) return photoDate
    if (context == null || name.removeSuffix(".enc").substringAfterLast('.').lowercase() !in
        listOf("mp4", "mov", "webm", "avi", "mkv", "3gp")) return null
    return runCatching {
        val temp = java.io.File.createTempFile("vault-date-", ".tmp", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(temp.absolutePath)
                val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty()
                listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'").firstNotNullOfOrNull { pattern ->
                    runCatching { SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(value)?.time?.takeIf { it > 0 } }.getOrNull()
                }
            } finally { retriever.release() }
        } finally { temp.delete() }
    }.getOrNull()
}

internal fun sourceFileDate(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        listOf("datetaken", "date_added", "last_modified").firstNotNullOfOrNull { column ->
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).takeIf { it > 0 }
                ?.let { if (column == "date_added") it * 1000 else it }
        }
    }
}.getOrNull()
