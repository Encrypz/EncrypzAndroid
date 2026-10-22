package com.encrypz.privacy.ui.vault

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VaultImportsTest {
    @Test fun acceptsOneThroughThirtyFilesAndRejectsLargerSelections() {
        assertTrue(validImportCount(1))
        assertTrue(validImportCount(30))
        assertFalse(validImportCount(31))
        assertFalse(validImportCount(0))
    }

    @Test fun preservesCompleteFilesIncludingEmptyFiles() {
        assertArrayEquals(byteArrayOf(), readImportBytes(byteArrayOf().inputStream(), 4))
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readImportBytes(bytes.inputStream(), 4))
    }
    @Test(expected = IOException::class) fun oversizedFilesAreRejectedRatherThanTruncated() {
        readImportBytes(byteArrayOf(1, 2, 3, 4, 5).inputStream(), 4)
    }
    @Test(expected = IOException::class) fun readErrorsAreNotReplacedWithFakeContent() {
        readImportBytes(object : java.io.InputStream() {
            override fun read(): Int = throw IOException("Read failed")
        })
    }
}
