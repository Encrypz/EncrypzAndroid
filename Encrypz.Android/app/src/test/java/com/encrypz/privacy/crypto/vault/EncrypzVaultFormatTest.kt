package com.encrypz.privacy.crypto.vault

import com.encrypz.privacy.vault.EncrypzVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class EncrypzVaultFormatTest {

    @Test
    fun defaultScryptMasterkeyAndVaultManifest_createUnlockAndVerify() {
        val password = "Encrypz#Default2026".toCharArray()
        val created = VaultMasterkey.createVault(password)
        val originalRaw = created.masterkeySet.raw64Bytes()
        created.masterkeySet.close()

        val manifestMap = VaultEncoding.parseFlatJsonObject(created.vaultManifestJson)
        assertEquals("encrypz.vault.v2", manifestMap["schema"])
        assertEquals(2L, manifestMap["vaultVersion"])
        assertEquals("ENCRYPZ_AES256_SIV_GCM_V2", manifestMap["cipherSuite"])
        assertEquals(65536L, manifestMap["chunkSizeBytes"])

        val keyfileMap = VaultEncoding.parseFlatJsonObject(created.masterkeyJson)
        assertEquals("encrypz.key.v2", keyfileMap["schema"])
        assertEquals(2L, keyfileMap["keyVersion"])
        assertEquals("scrypt", keyfileMap["kdf"])

        val unlocked = VaultMasterkey.unlockVault(
            vaultManifestJson = created.vaultManifestJson,
            masterkeyJson = created.masterkeyJson,
            password = "Encrypz#Default2026".toCharArray()
        )
        assertArrayEquals(originalRaw, unlocked.masterkeySet.raw64Bytes())
        assertEquals(2, unlocked.vaultConfig.format)
        assertEquals("ENCRYPZ_AES256_SIV_GCM_V2", unlocked.vaultConfig.cipherSuite)
        assertEquals(created.vaultConfig.vaultId, unlocked.vaultConfig.vaultId)
        unlocked.masterkeySet.close()

        assertThrows(AEADBadTagException::class.java) {
            VaultMasterkey.unlockVault(
                vaultManifestJson = created.vaultManifestJson,
                masterkeyJson = created.masterkeyJson,
                password = "WrongPassword#2026".toCharArray()
            )
        }
    }

    @Test
    fun vaultManifestAndMasterkey_rejectTamperedVersionOrKdfParams() {
        val created = VaultMasterkey.createVault("VaultManifest#2026".toCharArray(), scryptCostParam = 1024)
        created.masterkeySet.close()

        val forgedManifest = created.vaultManifestJson.replace("\"vaultVersion\":2", "\"vaultVersion\":1")
        assertThrows(AEADBadTagException::class.java) {
            VaultMasterkey.unlockVault(forgedManifest, created.masterkeyJson, "VaultManifest#2026".toCharArray())
        }

        val forgedKeyfile = created.masterkeyJson.replace("\"keyVersion\":2", "\"keyVersion\":3")
        assertThrows(AEADBadTagException::class.java) {
            VaultMasterkey.unlockVault(created.vaultManifestJson, forgedKeyfile, "VaultManifest#2026".toCharArray())
        }
    }

    @Test
    fun changePassword_preservesMasterkeyWithoutFileReencryption() {
        val oldPass = "OldMasterPassword#2026"
        val newPass = "NewMasterPassword#2026"
        val created = VaultMasterkey.createVault(oldPass.toCharArray(), scryptCostParam = 1024)
        val expectedRaw = created.masterkeySet.raw64Bytes()
        val sampleEncrypted = VaultFileCrypto.encryptBytes("keep existing ciphertext".toByteArray(), created.masterkeySet)
        created.masterkeySet.close()

        val updatedMasterkeyJson = VaultMasterkey.changePassword(
            vaultManifestJson = created.vaultManifestJson,
            masterkeyJson = created.masterkeyJson,
            currentPassword = oldPass.toCharArray(),
            newPassword = newPass.toCharArray()
        )

        assertThrows(AEADBadTagException::class.java) {
            VaultMasterkey.unlockVault(created.vaultManifestJson, updatedMasterkeyJson, oldPass.toCharArray())
        }

        val reopened = VaultMasterkey.unlockVault(created.vaultManifestJson, updatedMasterkeyJson, newPass.toCharArray())
        assertArrayEquals(expectedRaw, reopened.masterkeySet.raw64Bytes())
        assertEquals(
            "keep existing ciphertext",
            VaultFileCrypto.decryptBytes(sampleEncrypted, reopened.masterkeySet).decodeToString()
        )
        reopened.masterkeySet.close()
    }

    @Test
    fun recoveryKey_recoversVaultAndDetectsTyposViaChecksum() {
        val created = VaultMasterkey.createVault("Original#2026Pass".toCharArray(), scryptCostParam = 1024)
        val expectedRaw = created.masterkeySet.raw64Bytes()
        created.masterkeySet.close()

        // Flip one character in the recovery key to ensure checksum rejects corruption.
        val chars = created.recoveryKey.toCharArray()
        val flipIdx = chars.indexOfFirst { it.isLetterOrDigit() }
        chars[flipIdx] = if (chars[flipIdx] == 'A') 'B' else 'A'
        val corruptedKey = String(chars)
        assertThrows(IllegalArgumentException::class.java) {
            VaultMasterkey.recoverMasterkey(
                created.vaultManifestJson,
                corruptedKey,
                "Recovered#2026Pass".toCharArray(),
                scryptCostParam = 1024
            )
        }

        val (recoveredSet, _, newMasterkeyJson) = VaultMasterkey.recoverMasterkey(
            created.vaultManifestJson,
            created.recoveryKey,
            "Recovered#2026Pass".toCharArray(),
            scryptCostParam = 1024
        )
        assertArrayEquals(expectedRaw, recoveredSet.raw64Bytes())
        recoveredSet.close()

        val unlocked = VaultMasterkey.unlockVault(
            created.vaultManifestJson,
            newMasterkeyJson,
            "Recovered#2026Pass".toCharArray()
        )
        assertArrayEquals(expectedRaw, unlocked.masterkeySet.raw64Bytes())
        unlocked.masterkeySet.close()
    }

    @Test
    fun pathCrypto_producesVaultShardsAndDirectoryBoundFilenames() {
        val created = VaultMasterkey.createVault("PathCrypto#2026!".toCharArray(), scryptCostParam = 1024)
        created.masterkeySet.use { mk ->
            val rootSegments = VaultPathCrypto.storagePathSegments("", mk)
            assertEquals(3, rootSegments.size)
            assertEquals("vault", rootSegments[0])
            assertEquals(2, rootSegments[1].length)
            assertEquals(30, rootSegments[2].length)

            val enc1 = VaultPathCrypto.encryptName("", "secret.pdf", mk)
            val enc2 = VaultPathCrypto.encryptName("dir-uuid-123", "secret.pdf", mk)
            assertTrue(enc1.endsWith(".epz"))
            assertFalse(enc1 == enc2)
            assertEquals("secret.pdf", VaultPathCrypto.decryptName("", enc1, mk))
            assertEquals("secret.pdf", VaultPathCrypto.decryptName("dir-uuid-123", enc2, mk))
            assertThrows(AEADBadTagException::class.java) {
                VaultPathCrypto.decryptName("wrong-dir", enc1, mk)
            }

            val longName = "very_long_file_name_".repeat(15) + ".txt"
            val encLong = VaultPathCrypto.encryptName("", longName, mk)
            val shortened = VaultPathCrypto.shortenIfNeeded(encLong, 200)
            assertNotNull(shortened)
            assertTrue(shortened!!.endsWith(".epzl"))
            assertNull(VaultPathCrypto.shortenIfNeeded(enc1, 200))
        }
    }

    @Test
    fun fileCrypto_handlesEpz2MagicEmptyExactChunkMultiChunkAndTampering() {
        val encKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // 0 bytes (68-byte EPZ2 header only)
        val emptyEnc = VaultFileCrypto.encryptBytes(ByteArray(0), encKey)
        assertEquals(VaultFileCrypto.HEADER_SIZE, emptyEnc.size)
        assertEquals("EPZ2", emptyEnc.copyOfRange(0, 4).decodeToString())
        assertArrayEquals(ByteArray(0), VaultFileCrypto.decryptBytes(emptyEnc, encKey))

        // Tampering with header version byte triggers AEADBadTagException via header AAD
        val tamperedVersion = emptyEnc.copyOf().apply { this[5] = 9 }
        assertThrows(AEADBadTagException::class.java) {
            VaultFileCrypto.decryptBytes(tamperedVersion, encKey)
        }

        // Exact 64 KiB chunk
        val exact64K = ByteArray(VaultFileCrypto.CLEARTEXT_CHUNK_SIZE) { (it % 251).toByte() }
        val encExact = VaultFileCrypto.encryptBytes(exact64K, encKey)
        assertArrayEquals(exact64K, VaultFileCrypto.decryptBytes(encExact, encKey))

        // Multi-chunk (200 KiB = 4 chunks) streaming
        val multi = ByteArray(200 * 1024) { ((it * 17) and 0xFF).toByte() }
        val encStreamOut = ByteArrayOutputStream()
        VaultFileCrypto.encryptStream(ByteArrayInputStream(multi), encStreamOut, encKey)
        val encMulti = encStreamOut.toByteArray()
        val decStreamOut = ByteArrayOutputStream()
        VaultFileCrypto.decryptStream(ByteArrayInputStream(encMulti), decStreamOut, encKey)
        assertArrayEquals(multi, decStreamOut.toByteArray())

        // Reordering chunk 0 and chunk 1 must fail AAD chunk-number check!
        val swapped = encMulti.copyOf()
        val c0Start = VaultFileCrypto.HEADER_SIZE
        val c1Start = c0Start + VaultFileCrypto.CIPHERTEXT_CHUNK_SIZE
        val chunkLen = VaultFileCrypto.CIPHERTEXT_CHUNK_SIZE
        val tmp = swapped.copyOfRange(c0Start, c0Start + chunkLen)
        System.arraycopy(swapped, c1Start, swapped, c0Start, chunkLen)
        System.arraycopy(tmp, 0, swapped, c1Start, chunkLen)
        assertThrows(AEADBadTagException::class.java) {
            VaultFileCrypto.decryptBytes(swapped, encKey)
        }

        // Wrong key must fail header decryption.
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertThrows(AEADBadTagException::class.java) {
            VaultFileCrypto.decryptBytes(encMulti, wrongKey)
        }
    }

    @Test
    fun vaultEngineLocalBackend_endToEndFoldersFilesMovePasswordChangeAndScan() {
        val tempRoot = Files.createTempDirectory("encrypz_test_vault").toFile()
        try {
            val backend = EncrypzVaultEngine.LocalVaultBackend(tempRoot)
            val created = EncrypzVaultEngine.initializeVault(backend, "EnginePass#2026!".toCharArray(), scryptCostParam = 1024)
            assertTrue(File(tempRoot, "vault.encrypz").exists())
            assertTrue(File(tempRoot, "masterkey.encrypz").exists())
            assertTrue(File(tempRoot, "vault").exists())

            val longFileName = "long_secret_document_".repeat(12) + ".txt"
            created.masterkeySet.use { mk ->
                val cfg = created.vaultConfig
                val folderA = EncrypzVaultEngine.createFolder(backend, mk, cfg, null, "Finance")
                val folderB = EncrypzVaultEngine.createFolder(backend, mk, cfg, folderA, "Taxes 2026")

                val fileRoot = EncrypzVaultEngine.writeEncryptedFile(
                    backend, mk, cfg, null, "root_note.txt", "hello root".toByteArray()
                )
                val fileLong = EncrypzVaultEngine.writeEncryptedFile(
                    backend, mk, cfg, folderB, longFileName, "deep shortened file".toByteArray()
                )
                assertNotNull(fileLong.shortenedDirName)

                // Move root_note.txt into Finance folder without re-encrypting payload
                EncrypzVaultEngine.moveEncryptedFile(
                    backend = backend,
                    masterkey = mk,
                    vaultConfig = cfg,
                    oldParentDirId = null,
                    newParentDirId = folderA,
                    cleartextName = "root_note.txt",
                    encryptedPayload = fileRoot.encryptedPayload,
                    oldRemoteId = fileRoot.remoteId
                )
            }

            // Change password & reopen vault from disk
            EncrypzVaultEngine.changePassword(
                backend,
                "EnginePass#2026!".toCharArray(),
                "NewEnginePass#2026!".toCharArray()
            )

            val reopened = EncrypzVaultEngine.unlockVault(backend, "NewEnginePass#2026!".toCharArray())
            reopened.masterkeySet.use { mk ->
                val tree = EncrypzVaultEngine.scanVaultTree(backend, mk, reopened.vaultConfig)
                assertEquals(0, tree.unreadableCount)
                assertEquals(2, tree.folders.size)
                val finance = tree.folders.first { it.name == "Finance" }
                val taxes = tree.folders.first { it.name == "Taxes 2026" }
                assertNull(finance.parentDirId)
                assertEquals(finance.dirId, taxes.parentDirId)

                assertEquals(2, tree.files.size)
                val movedRoot = tree.files.first { it.name == "root_note.txt" }
                val deepLong = tree.files.first { it.name == longFileName }
                assertEquals(finance.dirId, movedRoot.parentDirId)
                assertEquals(taxes.dirId, deepLong.parentDirId)
                assertEquals("hello root", VaultFileCrypto.decryptBytes(movedRoot.encryptedBytes, mk).decodeToString())
                assertEquals("deep shortened file", VaultFileCrypto.decryptBytes(deepLong.encryptedBytes, mk).decodeToString())
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
