package com.encrypz.privacy.vault

import com.encrypz.privacy.crypto.vault.VaultMasterkey

enum class VaultStorageType {
    LOCAL,
    DEVICE_FOLDER,
    GOOGLE_DRIVE
}

/**
 * Metadata descriptor for a registered Encrypz zero-knowledge vault (database-free).
 * Contains only location/display metadata — never master keys or passwords.
 */
data class VaultDescriptor(
    val id: String,
    val name: String,
    val storageType: VaultStorageType,
    val localDirPath: String? = null,
    val deviceFolderTreeUri: String? = null,
    val googleAccountEmail: String? = null,
    val driveRootFolderId: String? = null,
    val driveFolderName: String? = null,
    val formatVersion: Int = VaultMasterkey.SUPPORTED_VAULT_FORMAT,
    val createdAt: String = ""
) {
    val isOfflineLocal: Boolean
        get() = storageType == VaultStorageType.LOCAL || storageType == VaultStorageType.DEVICE_FOLDER

    val isEncrypzV2: Boolean
        get() = formatVersion == VaultMasterkey.SUPPORTED_VAULT_FORMAT
}

/**
 * Local device key metadata record (PIN verifier, Drive metadata, legacy compat).
 * Replaces the old database record; stored exclusively on-device or in Drive vault files.
 */
data class VaultKeyRecord(
    val email: String = "",
    val passwordSaltHex: String = "",
    val passwordVerifierHex: String = "",
    val wrappedVaultKeyHex: String = "",
    val pinVerifierHex: String? = null,
    val hasLoggedInOnce: Boolean = true,
    val driveFolderId: String? = null,
    val googleRefreshToken: String? = null,
    val updatedAt: String = "",
    val credentialVersion: Int = 8,
    val vaultId: String = ""
)

data class DiscoveredDriveVault(
    val folderId: String,
    val folderName: String,
    val formatVersion: Int,
    val vaultId: String = ""
)

data class VaultCreationResult(
    val descriptor: VaultDescriptor,
    val recoveryKey: String
)
