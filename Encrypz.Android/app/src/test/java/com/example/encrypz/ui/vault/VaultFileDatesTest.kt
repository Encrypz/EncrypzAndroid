package com.example.encrypz.ui.vault

import org.junit.Assert.*
import org.junit.Test

class VaultFileDatesTest {
    private fun file(id: String, created: Long?, upload: String = "Oct 19, 2026") = VaultFileEntry(
        id = id, name = "$id.jpg.enc", sizeBytes = 1, uploadedAt = upload,
        createdAtMillis = created, googleAccountEmail = "", encryptedPayload = byteArrayOf(), ivHex = ""
    )

    @Test fun creationDateOverridesUploadOrderAndKeepsTimeWithinDay() {
        val morning = parseVaultDate("2020:01:02 08:00:00", "yyyy:MM:dd HH:mm:ss")!!
        val evening = morning + 3600000
        val newer = file("newer", evening, "Jan 1, 2021")
        val older = file("older", morning)
        val state = VaultUiState(files = listOf(older, newer))
        assertEquals(listOf("newer", "older"), state.visibleFiles.map { it.id })
        assertEquals("Jan 2, 2020", newer.creationDateLabel)
        assertEquals(older.creationDateLabel, newer.creationDateLabel)
    }

    @Test fun legacyDatesAndMissingDatesHaveStableFallbacks() {
        val legacy = file("legacy", null)
        assertEquals(parseVaultDate("Oct 19, 2026"), legacy.sortDate)
        val missing = file("missing", null, "invalid")
        assertEquals(0L, missing.sortDate)
        assertEquals("Unknown date", missing.creationDateLabel)
        assertNull(parseVaultDate("2026:02:31 00:00:00", "yyyy:MM:dd HH:mm:ss"))
        assertEquals(listOf("legacy", "missing"),
            VaultUiState(files = listOf(missing, legacy)).visibleFiles.map { it.id })
    }
}
