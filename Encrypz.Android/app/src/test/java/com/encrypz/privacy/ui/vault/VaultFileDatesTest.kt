package com.encrypz.privacy.ui.vault

import org.junit.Assert.*
import org.junit.Test

class VaultFileDatesTest {
    @Test fun dateSearchSupportsDaysMonthsYearsAndNames() {
        val item = file("holiday", parseVaultDate("2020:01:02 23:59:59", "yyyy:MM:dd HH:mm:ss"))
        listOf("2020-01-02", "2020-01", "2020", "Jan 2, 2020", "January 2020", " jan 2020 ", "HOLIDAY")
            .forEach { assertTrue(it, item.matchesVaultSearch(it)) }
        listOf("2020-01-03", "2026", "February 2020")
            .forEach { assertFalse(it, item.matchesVaultSearch(it)) }
    }

    @Test fun dateSearchUsesUploadFallbackAndExcludesUnknownDates() {
        assertTrue(file("legacy", null).matchesVaultSearch("2026-10-19"))
        assertFalse(file("missing", null, "invalid").matchesVaultSearch("1970"))
        assertTrue(file("missing", null, "invalid").matchesVaultSearch("missing"))
    }

    @Test fun dateSearchFindsFilesAcrossFoldersWhileRespectingCategoryAndDeletion() {
        val matching = file("matching", null).copy(folderId = "nested")
        val deleted = file("deleted", null).copy(isDeleted = true)
        val otherDay = file("other", null, "Oct 18, 2026")
        val state = VaultUiState(files = listOf(matching, deleted, otherDay), searchQuery = "2026-10-19")
        assertEquals(listOf("matching"), state.visibleFiles.map { it.id })
        assertTrue(state.copy(selectedCategory = VaultCategoryFilter.VIDEOS).visibleFiles.isEmpty())
    }

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
