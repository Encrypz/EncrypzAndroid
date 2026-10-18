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

    private fun accountId(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString("active_storage_account", "legacy") ?: "legacy"

    private fun accountPreferences(context: Context) = context.getSharedPreferences(
        if (accountId(context) == "legacy") PREFS_NAME else "${PREFS_NAME}_${accountId(context)}", Context.MODE_PRIVATE)

    /** Isolate each Google account's keys, PIN and files on this device. */
    @Synchronized
    fun selectAccount(context: Context, email: String): Boolean {
        val normalized = email.trim().lowercase(java.util.Locale.ROOT)
        val id = VaultCrypto.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)))
        if (accountId(context) == id) return false
        val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val target = context.getSharedPreferences("${PREFS_NAME}_$id", Context.MODE_PRIVATE)
        // Preserve legacy local data only for its recorded owner; leave originals intact.
        if (!target.contains(KEY_VAULT_KEY_B64) && legacy.contains("user_auth_record_$normalized")) {
            val editor = target.edit()
            for (key in listOf(KEY_VAULT_KEY_B64, KEY_VAULT_SALT_B64, "user_auth_record_$normalized")) {
                legacy.getString(key, null)?.let { editor.putString(key, it) }
            }
            val source = File(context.filesDir, VAULT_DIR_NAME)
            val destination = File(context.filesDir, "accounts/$id/$VAULT_DIR_NAME")
            if (source.exists()) check(source.copyRecursively(destination, overwrite = false))
            editor.putBoolean(KEY_INITIALIZED, legacy.getBoolean(KEY_INITIALIZED, false))
            check(editor.commit())
            val legacyEditor = legacy.edit()
            legacy.getString(KEY_VAULT_KEY_B64, null)?.takeUnless { it.startsWith("ks1:") }?.let {
                legacyEditor.putString(KEY_VAULT_KEY_B64, DeviceSecrets.seal(Base64.decode(it, Base64.NO_WRAP)))
            }
            legacy.getString("user_auth_record_$normalized", null)?.takeUnless { it.startsWith("ks1:") }?.let {
                legacyEditor.putString("user_auth_record_$normalized", DeviceSecrets.seal(it.toByteArray(Charsets.UTF_8)))
            }
            check(legacyEditor.commit())
        }
        check(legacy.edit().putString("active_storage_account", id).commit())
        return true
    }

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
        val prefs = accountPreferences(context)
        val existingKeyB64 = prefs.getString(KEY_VAULT_KEY_B64, null)
        val existingSaltB64 = prefs.getString(KEY_VAULT_SALT_B64, null)

        if (existingKeyB64 != null && existingSaltB64 != null) {
            val keyBytes = if (existingKeyB64.startsWith("ks1:")) DeviceSecrets.open(existingKeyB64)
                else Base64.decode(existingKeyB64, Base64.NO_WRAP)
            require(keyBytes.size == 32) { "Stored vault key is invalid. Restore with your master password." }
            val bundle = PersistedVaultKeyBundle(SecretKeySpec(keyBytes, "AES"), Base64.decode(existingSaltB64, Base64.NO_WRAP))
            if (!existingKeyB64.startsWith("ks1:")) saveKeyBundle(context, bundle.vaultKey, bundle.salt)
            return bundle
        }
        val bundle = PersistedVaultKeyBundle(VaultCrypto.generateVaultKey(), VaultCrypto.generateSalt())
        saveKeyBundle(context, bundle.vaultKey, bundle.salt)
        return bundle
    }

    fun saveKeyBundle(context: Context, key: SecretKey, salt: ByteArray) {
        check(accountPreferences(context).edit()
            .putString(KEY_VAULT_KEY_B64, DeviceSecrets.seal(key.encoded))
            .putString(KEY_VAULT_SALT_B64, Base64.encodeToString(salt, Base64.NO_WRAP)).commit())
    }

    fun isInitialized(context: Context): Boolean {
        val prefs = accountPreferences(context)
        val manifestFile = File(getVaultRootDir(context), MANIFEST_FILE_NAME)
        return prefs.getBoolean(KEY_INITIALIZED, false) && manifestFile.exists()
    }

    @Synchronized
    fun saveUserAuthRecord(
        context: Context,
        record: MongoAtlasClient.MongoVaultUserRecord
    ) {
        val keyEmail = record.email.trim().lowercase().ifBlank { "default_vault_user" }
        val prefs = accountPreferences(context)
        val json = JSONObject().apply {
            put("email", keyEmail)
            put("credentialVersion", record.credentialVersion)
            put("passwordSaltHex", record.passwordSaltHex)
            put("passwordVerifierHex", record.passwordVerifierHex)
            put("wrappedVaultKeyHex", record.wrappedVaultKeyHex)
            put("pinVerifierHex", record.pinVerifierHex ?: "")
            put("hasLoggedInOnce", record.hasLoggedInOnce)
            put("driveFolderId", record.driveFolderId ?: "")
            put("googleRefreshToken", record.googleRefreshToken ?: "")
        }
        prefs.edit()
            .putString("user_auth_record_$keyEmail", DeviceSecrets.seal(json.toString().toByteArray(Charsets.UTF_8)))
            .apply()
    }

    @Synchronized
    fun loadUserAuthRecord(
        context: Context,
        email: String
    ): MongoAtlasClient.MongoVaultUserRecord? {
        val keyEmail = email.trim().lowercase().ifBlank { "default_vault_user" }
        val prefs = accountPreferences(context)
        val raw = prefs.getString("user_auth_record_$keyEmail", null)
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(if (raw.startsWith("ks1:")) String(DeviceSecrets.open(raw), Charsets.UTF_8) else raw)
            MongoAtlasClient.MongoVaultUserRecord(
                email = json.optString("email", keyEmail),
                credentialVersion = json.optInt("credentialVersion", 1),
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

            accountPreferences(context)
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
        val id = accountId(context)
        val dir = File(context.filesDir, if (id == "legacy") VAULT_DIR_NAME else "accounts/$id/$VAULT_DIR_NAME")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getBlobsDir(context: Context): File {
        val dir = File(getVaultRootDir(context), BLOBS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

