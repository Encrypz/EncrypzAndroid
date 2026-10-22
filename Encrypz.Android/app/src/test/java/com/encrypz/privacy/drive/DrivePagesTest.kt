package com.encrypz.privacy.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivePagesTest {
    @Test fun restoresMoreThanOneHundredFiles() {
        val results = DrivePages.collect { token ->
            if (token == null) DrivePages.Page((1..100).toList(), "page2")
            else DrivePages.Page((101..205).toList(), null)
        }
        assertEquals((1..205).toList(), results)
    }
    @Test(expected = IllegalStateException::class) fun rejectsRepeatedPageTokens() {
        DrivePages.collect { DrivePages.Page(listOf("file"), "same") }
    }
    @Test fun emptyDriveHasNoFiles() {
        assertEquals(emptyList<String>(), DrivePages.collect { DrivePages.Page(emptyList<String>(), null) })
    }
}
