package com.example.encrypz.data

import android.content.Context
import android.util.Base64
import com.example.encrypz.crypto.VaultCrypto
import com.example.encrypz.ui.vault.DriveSyncState
import com.example.encrypz.ui.vault.VaultFileEntry
import com.example.encrypz.ui.vault.VaultFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Persists the user's AES-256-GCM encrypted vault files (.enc blobs), encrypted thumbnails,
 * encrypted folders, and the 256-bit vault key in private app storage so uploaded files
 * remain saved across app restarts and sync cleanly with Google Drive.
 */
object LocalVaultStorage {
    private const val PREFS_NAME = "encrypz_vault_storage_prefs"
    private const val KEY_VAULT_KEY_B64 = "vault_aes256_key_b64"
    private const val KEY_VAULT_SALT_B64 = "vault_pbkdf2_salt_b64"
    private const val KEY_INITIALIZED = "vault_storage_initialized_v2"

    private const val VAULT_DIR_NAME = "encrypz_vault"
    private const val BLOBS_DIR_NAME = "blobs"
    private const val MANIFEST_FILE_NAME = "vault_manifest_v2.json"

    data class PersistedVaultKeyBundle(
        val vaultKey: SecretKey,
        val salt: ByteArray
    )

    data class PersistedVaultData(
        val folders: List<VaultFolder>,
        val files: List<VaultFileEntry>
    )

    /**
     * Loads the persistent 256-bit AES-GCM vault key and salt, or generates and persists them
     * on first run so all encrypted files (.enc) saved locally or on Google Drive can always
     * be decrypted in future sessions.
     */
    @Synchronized
    fun loadOrCreateKeyBundle(context: Context): PersistedVaultKeyBundle {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingKeyB64 = prefs.getString(KEY_VAULT_KEY_B64, null)
        val existingSaltB64 = prefs.getString(KEY_VAULT_SALT_B64, null)

        if (!existingKeyB64.isNullOrBlank() && !existingSaltB64.isNullOrBlank()) {
            try {
                val keyBytes = Base64.decode(existingKeyB64, Base64.NO_WRAP)
                val saltBytes = Base64.decode(existingSaltB64, Base64.NO_WRAP)
                if (keyBytes.size == 32 && saltBytes.isNotEmpty()) {
                    return PersistedVaultKeyBundle(
                        vaultKey = SecretKeySpec(keyBytes, "AES"),
                        salt = saltBytes
                    )
                }
            } catch (_: Exception) {
                // Fall through and regenerate if corrupted
            }
        }

        val newKey = VaultCrypto.generateVaultKey()
        val newSalt = VaultCrypto.generateSalt()
        prefs.edit()
            .putString(KEY_VAULT_KEY_B64, Base64.encodeToString(newKey.encoded, Base64.NO_WRAP))
            .putString(KEY_VAULT_SALT_B64, Base64.encodeToString(newSalt, Base64.NO_WRAP))
            .apply()

        return PersistedVaultKeyBundle(vaultKey = newKey, salt = newSalt)
    }

    fun isInitialized(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manifestFile = File(getVaultRootDir(context), MANIFEST_FILE_NAME)
        return prefs.getBoolean(KEY_INITIALIZED, false) && manifestFile.exists()
    }

    @Synchronized
    fun saveUserAuthRecord(
        context: Context,
        record: MongoAtlasClient.MongoVaultUserRecord
    ) {
        val keyEmail = record.email.trim().lowercase().ifBlank { "default_vault_user" }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("email", keyEmail)
            put("passwordSaltHex", record.passwordSaltHex)
            put("passwordVerifierHex", record.passwordVerifierHex)
            put("wrappedVaultKeyHex", record.wrappedVaultKeyHex)
            put("pinVerifierHex", record.pinVerifierHex ?: "")
            put("hasLoggedInOnce", record.hasLoggedInOnce)
            put("driveFolderId", record.driveFolderId ?: "")
            put("googleRefreshToken", record.googleRefreshToken ?: "")
        }
        prefs.edit()
            .putString("user_auth_record_$keyEmail", json.toString())
            .apply()
    }

    @Synchronized
    fun loadUserAuthRecord(
        context: Context,
        email: String
    ): MongoAtlasClient.MongoVaultUserRecord? {
        val keyEmail = email.trim().lowercase().ifBlank { "default_vault_user" }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("user_auth_record_$keyEmail", null)
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            MongoAtlasClient.MongoVaultUserRecord(
                email = json.optString("email", keyEmail),
                passwordSaltHex = json.getString("passwordSaltHex"),
                passwordVerifierHex = json.getString("passwordVerifierHex"),
                wrappedVaultKeyHex = json.getString("wrappedVaultKeyHex"),
                pinVerifierHex = json.optString("pinVerifierHex").takeIf { it.isNotBlank() },
                hasLoggedInOnce = json.optBoolean("hasLoggedInOnce", true),
                driveFolderId = json.optString("driveFolderId").takeIf { it.isNotBlank() },
                googleRefreshToken = json.optString("googleRefreshToken").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saves the full list of encrypted folders and encrypted file entries (.enc blobs) to disk.
     */
    @Synchronized
    fun saveVaultData(
        context: Context,
        folders: List<VaultFolder>,
        files: List<VaultFileEntry>
    ) {
        try {
            val rootDir = getVaultRootDir(context)
            val blobsDir = getBlobsDir(context)

            val activeIds = files.map { it.id }.toSet()

            // Write each file's AES-256-GCM encrypted payload (.enc) and optional encrypted thumbnail
            for (file in files) {
                val blobFile = File(blobsDir, "${file.id}.enc")
                if (!blobFile.exists() || blobFile.length() != file.encryptedPayload.size.toLong()) {
                    blobFile.writeBytes(file.encryptedPayload)
                }
                val thumbFile = File(blobsDir, "${file.id}.thumb.enc")
                if (file.encryptedThumbnail != null) {
                    if (!thumbFile.exists() || thumbFile.length() != file.encryptedThumbnail.size.toLong()) {
                        thumbFile.writeBytes(file.encryptedThumbnail)
                    }
                } else if (thumbFile.exists()) {
                    thumbFile.delete()
                }
            }

            // Clean up any shredded blob files no longer in the manifest
            blobsDir.listFiles()?.forEach { diskFile ->
                val baseId = diskFile.name
                    .removeSuffix(".thumb.enc")
                    .removeSuffix(".enc")
                if (baseId !in activeIds) {
                    diskFile.delete()
                }
            }

            val rootJson = JSONObject()
            val foldersArray = JSONArray()
            for (folder in folders) {
                val folderObj = JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("parentFolderId", folder.parentFolderId ?: JSONObject.NULL)
                    put("createdAt", folder.createdAt)
                    put("ivHex", folder.ivHex)
                    put("isDeleted", folder.isDeleted)
                }
                foldersArray.put(folderObj)
            }

            val filesArray = JSONArray()
            for (file in files) {
                val fileObj = JSONObject().apply {
                    put("id", file.id)
                    put("name", file.name)
                    put("folderId", file.folderId ?: JSONObject.NULL)
                    put("sizeBytes", file.sizeBytes)
                    put("uploadedAt", file.uploadedAt)
                    put("googleAccountEmail", file.googleAccountEmail)
                    put("ivHex", file.ivHex)
                    put("isDeleted", file.isDeleted)
                    put("driveFileId", file.driveFileId ?: JSONObject.NULL)
                    put("driveSyncState", file.driveSyncState.name)
                }
                filesArray.put(fileObj)
            }

            rootJson.put("folders", foldersArray)
            rootJson.put("files", filesArray)

            val manifestFile = File(rootDir, MANIFEST_FILE_NAME)
            manifestFile.writeText(rootJson.toString())

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_INITIALIZED, true)
                .apply()
        } catch (_: Exception) {
            // Ignore disk write exceptions in preview/test environments
        }
    }

    /**
     * Loads persisted encrypted folders and encrypted file entries (.enc blobs) from disk.
     */
    @Synchronized
    fun loadVaultData(context: Context): PersistedVaultData? {
        try {
            val rootDir = getVaultRootDir(context)
            val blobsDir = getBlobsDir(context)
            val manifestFile = File(rootDir, MANIFEST_FILE_NAME)
            if (!manifestFile.exists()) return null

            val rootJson = JSONObject(manifestFile.readText())
            val foldersArray = rootJson.optJSONArray("folders") ?: JSONArray()
            val folders = mutableListOf<VaultFolder>()
            for (i in 0 until foldersArray.length()) {
                val obj = foldersArray.getJSONObject(i)
                val parentId = if (obj.isNull("parentFolderId")) null else obj.optString("parentFolderId").takeIf { it.isNotBlank() }
                folders.add(
                    VaultFolder(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        parentFolderId = parentId,
                        createdAt = obj.optString("createdAt", ""),
                        ivHex = obj.optString("ivHex", ""),
                        isDeleted = obj.optBoolean("isDeleted", false)
                    )
                )
            }

            val filesArray = rootJson.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<VaultFileEntry>()
            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                val id = obj.getString("id")
                val blobFile = File(blobsDir, "$id.enc")
                if (!blobFile.exists()) continue

                val encryptedPayload = blobFile.readBytes()
                val thumbFile = File(blobsDir, "$id.thumb.enc")
                val encryptedThumbnail = if (thumbFile.exists()) thumbFile.readBytes() else null
                val folderId = if (obj.isNull("folderId")) null else obj.optString("folderId").takeIf { it.isNotBlank() }
                val driveFileId = if (obj.isNull("driveFileId")) null else obj.optString("driveFileId").takeIf { it.isNotBlank() }
                val syncStateName = obj.optString("driveSyncState", DriveSyncState.LOCAL_ENCRYPTED.name)
                val driveSyncState = try {
                    DriveSyncState.valueOf(syncStateName)
                } catch (_: Exception) {
                    if (driveFileId != null) DriveSyncState.SYNCED_TO_DRIVE else DriveSyncState.LOCAL_ENCRYPTED
                }

                files.add(
                    VaultFileEntry(
                        id = id,
                        name = obj.getString("name"),
                        folderId = folderId,
                        sizeBytes = obj.optLong("sizeBytes", encryptedPayload.size.toLong()),
                        uploadedAt = obj.optString("uploadedAt", ""),
                        googleAccountEmail = obj.optString("googleAccountEmail", ""),
                        encryptedPayload = encryptedPayload,
                        encryptedThumbnail = encryptedThumbnail,
                        ivHex = obj.optString("ivHex", VaultCrypto.extractIvHex(encryptedPayload)),
                        isDeleted = obj.optBoolean("isDeleted", false),
                        driveFileId = driveFileId,
                        driveSyncState = driveSyncState
                    )
                )
            }

            return PersistedVaultData(folders = folders, files = files)
        } catch (_: Exception) {
            return null
        }
    }

    private fun getVaultRootDir(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getBlobsDir(context: Context): File {
        val dir = File(getVaultRootDir(context), BLOBS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

