package com.encrypz.privacy.vault

import android.content.Context
import com.encrypz.privacy.drive.GoogleDriveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypz Vault v2 [EncrypzVaultEngine.VaultBackend] backed by a Google Drive folder tree.
 */
class GoogleDriveVaultBackend(
    private val context: Context,
    private val accountEmail: String,
    private val vaultRootFolderId: String
) : EncrypzVaultEngine.VaultBackend {

    private val folderIdCache = ConcurrentHashMap<String, String>().apply {
        put("", vaultRootFolderId)
    }

    private fun requireToken(): String = runBlocking(Dispatchers.IO) {
        GoogleDriveSyncManager.getDriveAccessToken(context, accountEmail)
            ?: throw IllegalStateException("Google Drive authorization required. Tap Grant on the login screen or Security tab.")
    }

    private fun resolveDirId(
        accessToken: String,
        dirSegments: List<String>,
        createIfMissing: Boolean
    ): String? {
        if (dirSegments.isEmpty()) return vaultRootFolderId
        val cacheKey = dirSegments.joinToString("/")
        folderIdCache[cacheKey]?.let { return it }

        var currentId = vaultRootFolderId
        val prefix = mutableListOf<String>()
        for (segment in dirSegments) {
            prefix.add(segment)
            val key = prefix.joinToString("/")
            val cached = folderIdCache[key]
            if (cached != null) {
                currentId = cached
                continue
            }
            val nextId = if (createIfMissing) {
                GoogleDriveSyncManager.findOrCreateDriveSubfolder(accessToken, currentId, segment)
            } else {
                GoogleDriveSyncManager.findDriveSubfolder(accessToken, currentId, segment)
                    ?: return null
            }
            folderIdCache[key] = nextId
            currentId = nextId
        }
        return currentId
    }

    override fun readFile(segments: List<String>): ByteArray? {
        if (segments.isEmpty()) return null
        val token = runCatching { requireToken() }.getOrNull() ?: return null
        val parentId = resolveDirId(token, segments.dropLast(1), createIfMissing = false) ?: return null
        val fileMeta = GoogleDriveSyncManager.findDriveChildFile(token, parentId, segments.last()) ?: return null
        val fileId = fileMeta.optString("id")
        if (fileId.isBlank()) return null
        return GoogleDriveSyncManager.downloadDriveBytesById(token, fileId)
    }

    override fun writeFile(
        segments: List<String>,
        bytes: ByteArray,
        appProperties: Map<String, String>
    ): String? {
        require(segments.isNotEmpty()) { "File path segments cannot be empty" }
        val token = requireToken()
        val parentId = resolveDirId(token, segments.dropLast(1), createIfMissing = true)
            ?: error("Unable to resolve Drive directory")
        val existingId = GoogleDriveSyncManager.findDriveChildFile(token, parentId, segments.last())
            ?.optString("id")?.takeIf { it.isNotBlank() }
        return GoogleDriveSyncManager.uploadDriveChildBytes(
            accessToken = token,
            parentFolderId = parentId,
            fileName = segments.last(),
            payload = bytes,
            existingDriveId = existingId,
            appProperties = appProperties
        )
    }

    override fun deletePath(segments: List<String>): Boolean {
        if (segments.isEmpty()) return false
        val token = runCatching { requireToken() }.getOrNull() ?: return false
        val parentId = resolveDirId(token, segments.dropLast(1), createIfMissing = false) ?: return false
        val item = GoogleDriveSyncManager.findDriveChildAny(token, parentId, segments.last()) ?: return false
        val itemId = item.optString("id")
        if (itemId.isBlank()) return false
        folderIdCache.remove(segments.joinToString("/"))
        return GoogleDriveSyncManager.deleteDriveItemById(token, itemId)
    }

    override fun deleteByRemoteId(remoteId: String): Boolean {
        if (remoteId.isBlank() || remoteId.contains('/')) return false
        val token = runCatching { requireToken() }.getOrNull() ?: return false
        return GoogleDriveSyncManager.deleteDriveItemById(token, remoteId)
    }

    override fun ensureDirectory(segments: List<String>): String? {
        val token = requireToken()
        return resolveDirId(token, segments, createIfMissing = true)
    }

    override fun listDirectory(segments: List<String>): List<EncrypzVaultEngine.BackendEntry> {
        val token = runCatching { requireToken() }.getOrNull() ?: return emptyList()
        val dirId = resolveDirId(token, segments, createIfMissing = false) ?: return emptyList()
        val children = GoogleDriveSyncManager.listDriveChildren(token, dirId)
        val entries = mutableListOf<EncrypzVaultEngine.BackendEntry>()
        for (obj in children) {
            val id = obj.optString("id")
            val name = obj.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            val isDir = obj.optString("mimeType") == "application/vnd.google-apps.folder"
            if (isDir) {
                folderIdCache[(segments + name).joinToString("/")] = id
            }
            val propsObj = obj.optJSONObject("appProperties")
            val props = buildMap {
                if (propsObj != null) {
                    val keys = propsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, propsObj.optString(k))
                    }
                }
            }
            entries.add(
                EncrypzVaultEngine.BackendEntry(
                    name = name,
                    isDirectory = isDir,
                    remoteId = id,
                    sizeBytes = obj.optLong("size", 0L),
                    createdTime = obj.optString("createdTime", ""),
                    appProperties = props
                )
            )
        }
        return entries
    }
}
