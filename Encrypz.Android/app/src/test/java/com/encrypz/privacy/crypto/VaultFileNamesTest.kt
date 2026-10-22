package com.encrypz.privacy.crypto

import org.junit.Assert.*
import org.junit.Test

class VaultFileNamesTest {
    @Test fun namesRoundTripWithoutExposingOriginalNameOrExtension() {
        val key = VaultCrypto.generateVaultKey()
        val name = "Private records ? ????? ???.pdf.enc"
        val encrypted = VaultFileNames.encrypt(name, key)
        assertFalse(encrypted.contains("Private"))
        assertFalse(encrypted.contains(".pdf"))
        assertTrue(encrypted.endsWith(".enc"))
        assertEquals(name, VaultFileNames.decryptOrLegacy(encrypted, key))
        assertNotEquals(encrypted, VaultFileNames.encrypt(name, key))
    }

    @Test fun wrongKeyAndTamperingAreRejected() {
        val key = VaultCrypto.generateVaultKey()
        val encrypted = VaultFileNames.encrypt("secret.txt.enc", key)
        assertThrows(Exception::class.java) {
            VaultFileNames.decryptOrLegacy(encrypted, VaultCrypto.generateVaultKey())
        }
        val index = encrypted.length - 5
        val changed = encrypted.toCharArray().apply { this[index] = if (this[index] == '0') '1' else '0' }.concatToString()
        assertThrows(Exception::class.java) { VaultFileNames.decryptOrLegacy(changed, key) }
    }

    @Test fun legacyNamesAndLongNamesAreSupported() {
        val key = VaultCrypto.generateVaultKey()
        assertEquals("old.pdf.enc", VaultFileNames.decryptOrLegacy("old.pdf.enc", key))
        val longName = "?".repeat(240) + ".txt.enc"
        assertEquals(longName, VaultFileNames.decryptOrLegacy(VaultFileNames.encrypt(longName, key), key))
    }
}
