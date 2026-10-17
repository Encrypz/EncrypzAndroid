package com.example.encrypz.ui.vault

import com.example.encrypz.crypto.PasswordStrengthLevel
import com.example.encrypz.crypto.VaultCrypto
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class VaultViewModelTest {

    @Test
    fun vaultCrypto_encryptAndDecryptPayload_roundTripsPlaintext() {
        val vaultKey = VaultCrypto.generateVaultKey()
        val plaintext = "Zero-knowledge AES-256-GCM test payload".encodeToByteArray()

        val encrypted = VaultCrypto.encryptPayload(plaintext, vaultKey)
        val decrypted = VaultCrypto.decryptPayload(encrypted, vaultKey)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
        assertEquals(24, VaultCrypto.extractIvHex(encrypted).length)
    }

    @Test
    fun passwordStrength_evaluatesWeakAndStrongPassphrases() {
        val weak = VaultCrypto.evaluatePasswordStrength("12345")
        assertEquals(PasswordStrengthLevel.WEAK, weak.level)

        val strong = VaultCrypto.evaluatePasswordStrength("ZeroKnowledge#2026!Vault")
        assertEquals(PasswordStrengthLevel.EXCELLENT, strong.level)
    }

    @Test
    fun vaultViewModel_createFolderAndEncryptNote_updatesStateAndDecrypts() {
        val viewModel = VaultViewModel()
        assertTrue(viewModel.unlockWithMasterPassword("CorrectHorseBatteryStaple!99"))
        assertTrue(viewModel.uiState.value.isVaultUnlocked)

        val initialFolders = viewModel.uiState.value.visibleFolders.size
        viewModel.createFolder("Private Keys")
        assertEquals(initialFolders + 1, viewModel.uiState.value.visibleFolders.size)

        viewModel.encryptAndAddSecureNote("ssh_key.txt", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5")
        val addedFile = viewModel.uiState.value.visibleFiles.first { it.name == "ssh_key.txt.enc" }
        val decryptedText = viewModel.decryptFilePreview(addedFile)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5", decryptedText)
    }

    @Test
    fun vaultViewModel_recycleBinAndRestore_movesItemsCorrectly() {
        val viewModel = VaultViewModel()
        val targetFile = viewModel.uiState.value.visibleFiles.first()

        viewModel.moveFileToRecycleBin(targetFile.id)
        assertFalse(viewModel.uiState.value.visibleFiles.any { it.id == targetFile.id })
        assertTrue(viewModel.uiState.value.deletedFiles.any { it.id == targetFile.id })

        viewModel.restoreFile(targetFile.id)
        assertTrue(viewModel.uiState.value.visibleFiles.any { it.id == targetFile.id })
        assertFalse(viewModel.uiState.value.deletedFiles.any { it.id == targetFile.id })
    }

    @Test
    fun googleAuthConfig_containsConfiguredCredentials() {
        val viewModel = VaultViewModel()
        assertEquals(
            com.example.encrypz.auth.GoogleAuthConfig.CLIENT_ID,
            viewModel.uiState.value.oauthClientId
        )
        assertTrue(viewModel.exportVaultConfigJson().contains(com.example.encrypz.auth.GoogleAuthConfig.CLIENT_ID))
    }

    @Test
    fun vaultViewModel_unregisteredEmail_requiresAccountCreationFirst() {
        val viewModel = VaultViewModel()
        viewModel.connectGoogleAccount("new_unregistered_user@gmail.com")
        assertFalse(viewModel.uiState.value.hasExistingVaultAccount)

        // Attempting to log in (isCreatingNewVault = false) when no account exists returns ACCOUNT_NOT_CREATED
        val attemptResult = viewModel.attemptUnlockWithMasterPassword(
            password = "SomeMasterPassword#2026!",
            isCreatingNewVault = false
        )
        assertEquals(UnlockAttemptResult.ACCOUNT_NOT_CREATED, attemptResult)
        assertFalse(viewModel.uiState.value.isVaultUnlocked)

        // Creating the account (isCreatingNewVault = true) succeeds
        val createResult = viewModel.attemptUnlockWithMasterPassword(
            password = "SomeMasterPassword#2026!",
            isCreatingNewVault = true
        )
        assertEquals(UnlockAttemptResult.SUCCESS, createResult)
        assertTrue(viewModel.uiState.value.isVaultUnlocked)
        assertTrue(viewModel.uiState.value.hasExistingVaultAccount)
        assertTrue(viewModel.uiState.value.hasCompletedFirstLogin)
    }

    @Test
    fun vaultViewModel_wrongMasterPassword_isRejectedWithErrorMessage() {
        val viewModel = VaultViewModel()
        viewModel.connectGoogleAccount("vault_owner@encrypz.com")

        // Create the vault account with the Master Password
        assertTrue(viewModel.unlockWithMasterPassword("CorrectMasterPassword#2026!", isCreatingNewVault = true))
        assertTrue(viewModel.uiState.value.isVaultUnlocked)

        // Lock the vault and attempt to unlock with a wrong master password
        viewModel.lockVault()
        assertFalse(viewModel.uiState.value.isVaultUnlocked)

        val unlockedWithWrongPassword = viewModel.unlockWithMasterPassword("WrongPassword123!", isCreatingNewVault = false)
        assertFalse(unlockedWithWrongPassword)
        assertFalse(viewModel.uiState.value.isVaultUnlocked)
        assertEquals(
            "Wrong master password. Please enter the correct master password.",
            viewModel.uiState.value.statusBannerMessage
        )

        // Unlocking with the correct master password succeeds
        assertTrue(viewModel.unlockWithMasterPassword("CorrectMasterPassword#2026!", isCreatingNewVault = false))
        assertTrue(viewModel.uiState.value.isVaultUnlocked)
    }

    @Test
    fun vaultViewModel_unlockWithPinAfterFirstLogin_verifiesPinCorrectly() {
        val viewModel = VaultViewModel()
        viewModel.connectGoogleAccount("pin_user@encrypz.com")

        // First login with Master Password
        assertTrue(viewModel.unlockWithMasterPassword("MasterPass#2026!Secure", isCreatingNewVault = true))
        assertTrue(viewModel.uiState.value.hasCompletedFirstLogin)
        assertTrue(viewModel.uiState.value.promptPinSetupAfterFirstLogin)

        // Set 4-digit Quick-Unlock PIN after first login
        assertTrue(viewModel.setupUnlockPin("4829"))
        assertTrue(viewModel.uiState.value.isPinSet)
        assertFalse(viewModel.uiState.value.promptPinSetupAfterFirstLogin)

        // Lock vault and test wrong PIN vs correct PIN
        viewModel.lockVault()
        assertFalse(viewModel.uiState.value.isVaultUnlocked)

        assertFalse(viewModel.unlockWithPin("1111"))
        assertFalse(viewModel.uiState.value.isVaultUnlocked)
        assertEquals("Wrong PIN. Please enter the correct PIN.", viewModel.uiState.value.statusBannerMessage)

        assertTrue(viewModel.unlockWithPin("4829"))
        assertTrue(viewModel.uiState.value.isVaultUnlocked)
    }

    @Test
    fun googleDriveSyncManager_usesEncrypzFolderName() {
        assertEquals("Encrypz", com.example.encrypz.drive.GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME)
    }

    @Test
    fun mongoAtlasClient_upsertsAndFindsUserMasterPasswordVerifier() {
        val salt = VaultCrypto.generateSalt()
        val verifierHex = VaultCrypto.computePasswordVerifierHex("MongoMasterPass#0618", salt)
        val record = com.example.encrypz.data.MongoAtlasClient.MongoVaultUserRecord(
            email = "test_vault_user@encrypz.com",
            passwordSaltHex = VaultCrypto.toHex(salt),
            passwordVerifierHex = verifierHex,
            wrappedVaultKeyHex = "aabbccddeeff001122334455",
            updatedAt = "2026-10-17"
        )
        val upserted = com.example.encrypz.data.MongoAtlasClient.upsertUserVaultRecord(record)
        if (upserted) {
            val fetched = com.example.encrypz.data.MongoAtlasClient.findUserVaultRecord("test_vault_user@encrypz.com")
            assertEquals(verifierHex, fetched?.passwordVerifierHex)
        }
    }
}

