package com.encrypz.privacy.vault

import com.encrypz.privacy.crypto.vault.VaultFileCrypto
import com.encrypz.privacy.crypto.vault.VaultMasterkey
import com.encrypz.privacy.crypto.vault.VaultPathCrypto
import java.io.File
import java.util.UUID

/**
 * Storage-agnostic Encrypz Vault v2 (`EPZ2`) engine supporting local storage, phone folders, and Google Drive.
 */
object EncrypzVaultEngine {
    const val VAULT_CONFIG_FILE = "vault.encrypz"
    const val MASTERKEY_FILE = "masterkey.encrypz"

    data class BackendEntry(
        val name: String,
        val isDirectory: Boolean,
        val remoteId: String = "",
        val sizeBytes: Long = 0L,
        val createdTime: String = "",
        val appProperties: Map<String, String> = emptyMap()
    )

    interface VaultBackend {
        fun readFile(segments: List<String>): ByteArray?
        fun writeFile(
            segments: List<String>,
            bytes: ByteArray,
            appProperties: Map<String, String> = emptyMap()
        ): String?
        fun deletePath(segments: List<String>): Boolean
        fun deleteByRemoteId(remoteId: String): Boolean
        fun ensureDirectory(segments: List<String>): String?
        fun listDirectory(segments: List<String>): List<BackendEntry>
    }

    class LocalVaultBackend(val rootDir: File) : VaultBackend {
        init {
            if (!rootDir.exists()) rootDir.mkdirs()
        }

        private fun resolve(segments: List<String>): File {
            var current = rootDir
            for (seg in segments) {
                require(seg.isNotBlank() && seg != "." && seg != ".." && !seg.contains('/')) {
                    "Invalid path segment: $seg"
                }
                current = File(current, seg)
            }
            return current
        }

        override fun readFile(segments: List<String>): ByteArray? {
            val file = resolve(segments)
            if (!file.exists() || !file.isFile) return null
            return file.readBytes()
        }

        override fun writeFile(
            segments: List<String>,
            bytes: ByteArray,
            appProperties: Map<String, String>
        ): String {
            val target = resolve(segments)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            return segments.joinToString("/")
        }

        override fun deletePath(segments: List<String>): Boolean {
            val target = resolve(segments)
            if (!target.exists()) return false
            return target.deleteRecursively()
        }

        override fun deleteByRemoteId(remoteId: String): Boolean {
            if (remoteId.isBlank()) return false
            val segments = remoteId.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            return deletePath(segments)
        }

        override fun ensureDirectory(segments: List<String>): String {
            val dir = resolve(segments)
            if (!dir.exists()) dir.mkdirs()
            return segments.joinToString("/")
        }

        override fun listDirectory(segments: List<String>): List<BackendEntry> {
            val dir = resolve(segments)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            return dir.listFiles()?.map { child ->
                BackendEntry(
                    name = child.name,
                    isDirectory = child.isDirectory,
                    remoteId = (segments + child.name).joinToString("/"),
                    sizeBytes = if (child.isFile) child.length() else 0L
                )
            } ?: emptyList()
        }
    }

    data class ScannedFolder(
        val dirId: String,
        val name: String,
        val parentDirId: String?
    )

    data class ScannedFile(
        val name: String,
        val parentDirId: String?,
        val encryptedBytes: ByteArray,
        val remoteId: String,
        val encryptedCreationDate: String = "",
        val createdTime: String = ""
    )

    data class ScannedVaultTree(
        val folders: List<ScannedFolder>,
        val files: List<ScannedFile>,
        val unreadableCount: Int
    )

    data class StoredEncryptedFile(
        val encryptedName: String,
        val shortenedDirName: String?,
        val encryptedPayload: ByteArray,
        val remoteId: String
    )

    fun initializeVault(
        backend: VaultBackend,
        password: CharArray,
        scryptCostParam: Int = VaultMasterkey.DEFAULT_SCRYPT_COST
    ): VaultMasterkey.CreatedVault {
        val created = VaultMasterkey.createVault(
            password = password,
            scryptCostParam = scryptCostParam
        )
        backend.writeFile(listOf(MASTERKEY_FILE), created.masterkeyJson.toByteArray(Charsets.UTF_8))
        backend.writeFile(listOf(VAULT_CONFIG_FILE), created.vaultManifestJson.toByteArray(Charsets.UTF_8))
        val rootSegments = VaultPathCrypto.storagePathSegments(
            VaultPathCrypto.ROOT_DIR_ID,
            created.masterkeySet
        )
        backend.ensureDirectory(rootSegments)
        return created
    }

    fun unlockVault(
        backend: VaultBackend,
        password: CharArray
    ): VaultMasterkey.UnlockedVault {
        val vaultManifestBytes = backend.readFile(listOf(VAULT_CONFIG_FILE))
            ?: throw IllegalStateException("Missing vault.encrypz manifest in vault.")
        val masterkeyBytes = backend.readFile(listOf(MASTERKEY_FILE))
            ?: throw IllegalStateException("Missing masterkey.encrypz in vault.")
        return VaultMasterkey.unlockVault(
            vaultManifestJson = vaultManifestBytes.toString(Charsets.UTF_8),
            masterkeyJson = masterkeyBytes.toString(Charsets.UTF_8),
            password = password
        )
    }

    fun changePassword(
        backend: VaultBackend,
        currentPassword: CharArray,
        newPassword: CharArray
    ) {
        val vaultManifest = backend.readFile(listOf(VAULT_CONFIG_FILE))?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Missing vault.encrypz manifest in vault.")
        val masterkeyJson = backend.readFile(listOf(MASTERKEY_FILE))?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Missing masterkey.encrypz in vault.")
        val updatedJson = VaultMasterkey.changePassword(
            vaultManifestJson = vaultManifest,
            masterkeyJson = masterkeyJson,
            currentPassword = currentPassword,
            newPassword = newPassword
        )
        backend.writeFile(listOf(MASTERKEY_FILE), updatedJson.toByteArray(Charsets.UTF_8))
    }

    fun recoverVault(
        backend: VaultBackend,
        recoveryKey: String,
        newPassword: CharArray,
        scryptCostParam: Int = VaultMasterkey.DEFAULT_SCRYPT_COST
    ): VaultMasterkey.UnlockedVault {
        val vaultManifest = backend.readFile(listOf(VAULT_CONFIG_FILE))?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Missing vault.encrypz manifest in vault.")
        val (masterkey, config, newMasterkeyJson) = VaultMasterkey.recoverMasterkey(
            vaultManifestJson = vaultManifest,
            recoveryKey = recoveryKey,
            newPassword = newPassword,
            scryptCostParam = scryptCostParam
        )
        backend.writeFile(listOf(MASTERKEY_FILE), newMasterkeyJson.toByteArray(Charsets.UTF_8))
        return VaultMasterkey.UnlockedVault(masterkey, config)
    }

    fun exportVaultManifest(backend: VaultBackend): String? =
        backend.readFile(listOf(VAULT_CONFIG_FILE))?.toString(Charsets.UTF_8)

    fun createFolder(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig,
        parentDirId: String?,
        folderName: String,
        requestedDirId: String = UUID.randomUUID().toString()
    ): String {
        val parent = parentDirId.orEmpty()
        val parentSegments = VaultPathCrypto.storagePathSegments(parent, masterkey)
        val encryptedName = VaultPathCrypto.encryptName(parent, folderName, masterkey)
        val shortened = VaultPathCrypto.shortenIfNeeded(encryptedName, vaultConfig.maxNameLength)

        val dirEntrySegments = if (shortened != null) {
            val base = parentSegments + shortened
            backend.writeFile(base + VaultPathCrypto.LONG_NAME_META_FILE, encryptedName.toByteArray(Charsets.UTF_8))
            base
        } else {
            parentSegments + encryptedName
        }
        backend.writeFile(
            dirEntrySegments + VaultPathCrypto.FOLDER_POINTER_FILE,
            requestedDirId.toByteArray(Charsets.UTF_8)
        )
        val targetStorageSegments = VaultPathCrypto.storagePathSegments(requestedDirId, masterkey)
        backend.ensureDirectory(targetStorageSegments)
        return requestedDirId
    }

    fun writeEncryptedFile(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig,
        parentDirId: String?,
        cleartextName: String,
        cleartextBytes: ByteArray,
        encryptedPayloadOverride: ByteArray? = null,
        appProperties: Map<String, String> = emptyMap()
    ): StoredEncryptedFile {
        val parent = parentDirId.orEmpty()
        val parentSegments = VaultPathCrypto.storagePathSegments(parent, masterkey)
        val encryptedName = VaultPathCrypto.encryptName(parent, cleartextName, masterkey)
        val shortened = VaultPathCrypto.shortenIfNeeded(encryptedName, vaultConfig.maxNameLength)
        val encryptedPayload = encryptedPayloadOverride ?: VaultFileCrypto.encryptBytes(cleartextBytes, masterkey)

        val remoteId = if (shortened != null) {
            val shortenedDir = parentSegments + shortened
            backend.writeFile(
                shortenedDir + VaultPathCrypto.LONG_NAME_META_FILE,
                encryptedName.toByteArray(Charsets.UTF_8)
            )
            backend.writeFile(
                shortenedDir + VaultPathCrypto.LONG_FILE_BLOB_FILE,
                encryptedPayload,
                appProperties
            )
        } else {
            backend.writeFile(parentSegments + encryptedName, encryptedPayload, appProperties)
        }.orEmpty()

        return StoredEncryptedFile(
            encryptedName = encryptedName,
            shortenedDirName = shortened,
            encryptedPayload = encryptedPayload,
            remoteId = remoteId
        )
    }

    fun moveEncryptedFile(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig,
        oldParentDirId: String?,
        newParentDirId: String?,
        cleartextName: String,
        encryptedPayload: ByteArray,
        oldRemoteId: String?,
        appProperties: Map<String, String> = emptyMap()
    ): StoredEncryptedFile {
        val stored = writeEncryptedFile(
            backend = backend,
            masterkey = masterkey,
            vaultConfig = vaultConfig,
            parentDirId = newParentDirId,
            cleartextName = cleartextName,
            cleartextBytes = ByteArray(0),
            encryptedPayloadOverride = encryptedPayload,
            appProperties = appProperties
        )
        deleteEncryptedFile(
            backend = backend,
            masterkey = masterkey,
            vaultConfig = vaultConfig,
            parentDirId = oldParentDirId,
            cleartextName = cleartextName,
            remoteId = oldRemoteId
        )
        return stored
    }

    fun deleteEncryptedFile(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig,
        parentDirId: String?,
        cleartextName: String,
        remoteId: String?
    ): Boolean {
        val parent = parentDirId.orEmpty()
        val parentSegments = VaultPathCrypto.storagePathSegments(parent, masterkey)
        val encryptedName = VaultPathCrypto.encryptName(parent, cleartextName, masterkey)
        val shortened = VaultPathCrypto.shortenIfNeeded(encryptedName, vaultConfig.maxNameLength)
        val deletedByPath = if (shortened != null) {
            backend.deletePath(parentSegments + shortened)
        } else {
            backend.deletePath(parentSegments + encryptedName)
        }
        return if (deletedByPath || remoteId.isNullOrBlank()) {
            deletedByPath
        } else {
            backend.deleteByRemoteId(remoteId)
        }
    }

    fun deleteEncryptedFolder(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig,
        parentDirId: String?,
        folderName: String,
        dirId: String
    ) {
        val parent = parentDirId.orEmpty()
        val parentSegments = VaultPathCrypto.storagePathSegments(parent, masterkey)
        val encryptedName = VaultPathCrypto.encryptName(parent, folderName, masterkey)
        val shortened = VaultPathCrypto.shortenIfNeeded(encryptedName, vaultConfig.maxNameLength)
        if (shortened != null) {
            backend.deletePath(parentSegments + shortened)
        } else {
            backend.deletePath(parentSegments + encryptedName)
        }
        backend.deletePath(VaultPathCrypto.storagePathSegments(dirId, masterkey))
    }

    fun scanVaultTree(
        backend: VaultBackend,
        masterkey: VaultMasterkey.MasterkeySet,
        vaultConfig: VaultMasterkey.VaultConfig
    ): ScannedVaultTree {
        val folders = mutableListOf<ScannedFolder>()
        val files = mutableListOf<ScannedFile>()
        val visitedDirs = mutableSetOf<String>()
        var unreadableCount = 0

        fun walk(dirId: String) {
            if (!visitedDirs.add(dirId)) return
            val dirSegments = VaultPathCrypto.storagePathSegments(dirId, masterkey)
            val entries = backend.listDirectory(dirSegments)
            for (entry in entries) {
                if (entry.name.endsWith(VaultPathCrypto.VAULT_LONG_EXT) && entry.isDirectory) {
                    val shortenedSegments = dirSegments + entry.name
                    val realEncryptedName = backend.readFile(shortenedSegments + VaultPathCrypto.LONG_NAME_META_FILE)
                        ?.toString(Charsets.UTF_8)?.trim()
                    if (realEncryptedName.isNullOrBlank()) {
                        unreadableCount++
                        continue
                    }
                    val decryptedName = runCatching {
                        VaultPathCrypto.decryptName(dirId, realEncryptedName, masterkey)
                    }.getOrNull()
                    if (decryptedName == null) {
                        unreadableCount++
                        continue
                    }
                    val subEntries = backend.listDirectory(shortenedSegments)
                    val dirIdEntry = subEntries.firstOrNull { it.name == VaultPathCrypto.FOLDER_POINTER_FILE && !it.isDirectory }
                    val contentsEntry = subEntries.firstOrNull { it.name == VaultPathCrypto.LONG_FILE_BLOB_FILE && !it.isDirectory }
                    if (dirIdEntry != null) {
                        val childDirId = backend.readFile(shortenedSegments + VaultPathCrypto.FOLDER_POINTER_FILE)
                            ?.toString(Charsets.UTF_8)?.trim().orEmpty()
                        if (childDirId.isNotBlank()) {
                            folders.add(
                                ScannedFolder(
                                    dirId = childDirId,
                                    name = decryptedName,
                                    parentDirId = dirId.ifBlank { null }
                                )
                            )
                            walk(childDirId)
                        }
                    } else if (contentsEntry != null) {
                        val payload = backend.readFile(shortenedSegments + VaultPathCrypto.LONG_FILE_BLOB_FILE)
                        if (payload != null && payload.size >= VaultFileCrypto.HEADER_SIZE) {
                            files.add(
                                ScannedFile(
                                    name = decryptedName,
                                    parentDirId = dirId.ifBlank { null },
                                    encryptedBytes = payload,
                                    remoteId = contentsEntry.remoteId,
                                    encryptedCreationDate = contentsEntry.appProperties["encCreated"].orEmpty(),
                                    createdTime = contentsEntry.createdTime
                                )
                            )
                        } else {
                            unreadableCount++
                        }
                    }
                } else if (entry.name.endsWith(VaultPathCrypto.VAULT_FILE_EXT)) {
                    val decryptedName = runCatching {
                        VaultPathCrypto.decryptName(dirId, entry.name, masterkey)
                    }.getOrNull()
                    if (decryptedName == null) {
                        unreadableCount++
                        continue
                    }
                    if (entry.isDirectory) {
                        val folderSegments = dirSegments + entry.name
                        val childDirId = backend.readFile(folderSegments + VaultPathCrypto.FOLDER_POINTER_FILE)
                            ?.toString(Charsets.UTF_8)?.trim().orEmpty()
                        if (childDirId.isNotBlank()) {
                            folders.add(
                                ScannedFolder(
                                    dirId = childDirId,
                                    name = decryptedName,
                                    parentDirId = dirId.ifBlank { null }
                                )
                            )
                            walk(childDirId)
                        }
                    } else {
                        val payload = backend.readFile(dirSegments + entry.name)
                        if (payload != null && payload.size >= VaultFileCrypto.HEADER_SIZE) {
                            files.add(
                                ScannedFile(
                                    name = decryptedName,
                                    parentDirId = dirId.ifBlank { null },
                                    encryptedBytes = payload,
                                    remoteId = entry.remoteId,
                                    encryptedCreationDate = entry.appProperties["encCreated"].orEmpty(),
                                    createdTime = entry.createdTime
                                )
                            )
                        } else {
                            unreadableCount++
                        }
                    }
                }
            }
        }

        walk(VaultPathCrypto.ROOT_DIR_ID)
        return ScannedVaultTree(folders = folders, files = files, unreadableCount = unreadableCount)
    }
}
