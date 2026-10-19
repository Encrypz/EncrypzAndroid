package com.example.encrypz.ui.vault

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_IMPORT_FILES = 30
internal const val MAX_IMPORT_BYTES = 15 * 1024 * 1024

internal fun validImportCount(count: Int): Boolean = count in 1..MAX_IMPORT_FILES

/** Never return truncated data or substitute synthetic content for an unreadable file. */
internal fun readImportBytes(stream: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read > maxBytes - output.size()) throw IOException("File exceeds the 15 MiB file limit.")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
