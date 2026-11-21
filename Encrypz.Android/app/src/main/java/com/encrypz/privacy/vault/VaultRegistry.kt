package com.encrypz.privacy.vault

import android.content.Context
import com.encrypz.privacy.auth.GoogleAuthConfig
import com.encrypz.privacy.data.LocalVaultStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the user's local list of vaults (`LOCAL` and `GOOGLE_DRIVE`) without any external database.
 */
object VaultRegistry {
    private const val PREFS_NAME = "encrypz_vault_registry_v2"
    private const val KEY_VAULTS_JSON = "registered_vaults_json"
    private const val KEY_ACTIVE_VAULT_ID = "active_vault_id"
    private const val LOCAL_VAULTS_DIR = "encrypz_vaults"

    fun defaultLocalVaultDir(context: Context, vaultId: String): File {
        val dir = File(context.filesDir, "$LOCAL_VAULTS_DIR/$vaultId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun listVaults(context: Context): List<VaultDescriptor> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_VAULTS_JSON, null)
        if (raw.isNullOrBlank()) {
            val migrated = migrateLegacyGoogleVaultIfPresent(context)
            if (migrated != null) {
                saveVaultsLocked(context, listOf(migrated))
                setActiveVaultId(context, migrated.id)
                return listOf(migrated)
            }
            return emptyList()
        }
        return parseVaultList(raw)
    }

    @Synchronized
    fun getVault(context: Context, vaultId: String): VaultDescriptor? =
        listVaults(context).firstOrNull { it.id == vaultId }

    @Synchronized
    fun addOrUpdateVault(context: Context, descriptor: VaultDescriptor): List<VaultDescriptor> {
        val current = listVaults(context).toMutableList()
        val idx = current.indexOfFirst { it.id == descriptor.id }
        if (idx >= 0) {
            current[idx] = descriptor
        } else {
            current.add(0, descriptor)
        }
        saveVaultsLocked(context, current)
        return current
    }

    @Synchronized
    fun removeVault(
        context: Context,
        vaultId: String,
        deleteLocalFiles: Boolean = false
    ): List<VaultDescriptor> {
        val current = listVaults(context)
        val target = current.firstOrNull { it.id == vaultId }
        val updated = current.filterNot { it.id == vaultId }
        saveVaultsLocked(context, updated)
        if (getActiveVaultId(context) == vaultId) {
            setActiveVaultId(context, updated.firstOrNull()?.id)
        }
        if (deleteLocalFiles && target?.storageType == VaultStorageType.LOCAL) {
            target.localDirPath?.let { path ->
                runCatching { File(path).deleteRecursively() }
            }
        }
        return updated
    }

    fun getActiveVaultId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_VAULT_ID, null)
            ?.takeIf { it.isNotBlank() }

    fun setActiveVaultId(context: Context, vaultId: String?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (vaultId.isNullOrBlank()) editor.remove(KEY_ACTIVE_VAULT_ID)
        else editor.putString(KEY_ACTIVE_VAULT_ID, vaultId)
        editor.apply()
    }

    private fun saveVaultsLocked(context: Context, vaults: List<VaultDescriptor>) {
        val array = JSONArray()
        for (v in vaults) {
            array.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("name", v.name)
                    put("storageType", v.storageType.name)
                    put("localDirPath", v.localDirPath ?: JSONObject.NULL)
                    put("deviceFolderTreeUri", v.deviceFolderTreeUri ?: JSONObject.NULL)
                    put("googleAccountEmail", v.googleAccountEmail ?: JSONObject.NULL)
                    put("driveRootFolderId", v.driveRootFolderId ?: JSONObject.NULL)
                    put("driveFolderName", v.driveFolderName ?: JSONObject.NULL)
                    put("formatVersion", v.formatVersion)
                    put("createdAt", v.createdAt)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VAULTS_JSON, array.toString())
            .apply()
    }

    private fun parseVaultList(raw: String): List<VaultDescriptor> = runCatching {
        val array = JSONArray(raw)
        val result = mutableListOf<VaultDescriptor>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val storageName = obj.optString("storageType", VaultStorageType.LOCAL.name)
            val storageType = runCatching { VaultStorageType.valueOf(storageName) }
                .getOrDefault(VaultStorageType.LOCAL)
            result.add(
                VaultDescriptor(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    storageType = storageType,
                    localDirPath = obj.takeIf { !it.isNull("localDirPath") }
                        ?.optString("localDirPath")?.takeIf { it.isNotBlank() },
                    deviceFolderTreeUri = obj.takeIf { !it.isNull("deviceFolderTreeUri") }
                        ?.optString("deviceFolderTreeUri")?.takeIf { it.isNotBlank() },
                    googleAccountEmail = obj.takeIf { !it.isNull("googleAccountEmail") }
                        ?.optString("googleAccountEmail")?.takeIf { it.isNotBlank() },
                    driveRootFolderId = obj.takeIf { !it.isNull("driveRootFolderId") }
                        ?.optString("driveRootFolderId")?.takeIf { it.isNotBlank() },
                    driveFolderName = obj.takeIf { !it.isNull("driveFolderName") }
                        ?.optString("driveFolderName")?.takeIf { it.isNotBlank() },
                    formatVersion = obj.optInt("formatVersion", 2),
                    createdAt = obj.optString("createdAt", "")
                )
            )
        }
        result
    }.getOrDefault(emptyList())

    private fun migrateLegacyGoogleVaultIfPresent(context: Context): VaultDescriptor? {
        val savedEmail = GoogleAuthConfig.getSavedGoogleEmail(context)?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.contains("@") } ?: return null
        LocalVaultStorage.selectAccount(context, savedEmail)
        val record = LocalVaultStorage.loadUserAuthRecord(context, savedEmail) ?: return null
        return VaultDescriptor(
            id = "legacy-$savedEmail",
            name = "Encrypz ($savedEmail)",
            storageType = VaultStorageType.GOOGLE_DRIVE,
            googleAccountEmail = savedEmail,
            driveRootFolderId = record.driveFolderId,
            driveFolderName = "Encrypz",
            formatVersion = 1,
            createdAt = record.updatedAt.ifBlank {
                SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())
            }
        )
    }
}
