package com.encrypz.privacy.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * [EncrypzVaultEngine.VaultBackend] backed by a user-selected folder on the device
 * via Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`).
 */
class SafVaultBackend(
    private val context: Context,
    private val treeUri: Uri
) : EncrypzVaultEngine.VaultBackend {

    private val resolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val dirIdCache = ConcurrentHashMap<String, String>().apply {
        put("", rootDocId)
    }

    private data class DocChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    private fun childUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun queryChildren(parentDocId: String): List<DocChild> {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
        )
        val result = mutableListOf<DocChild>()
        resolver.query(childUri(parentDocId), projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                if (id.isNotBlank() && name.isNotBlank()) {
                    result.add(DocChild(id, name, mime, size))
                }
            }
        }
        return result
    }

    private fun resolveDirId(dirSegments: List<String>, createIfMissing: Boolean): String? {
        if (dirSegments.isEmpty()) return rootDocId
        val cacheKey = dirSegments.joinToString("/")
        dirIdCache[cacheKey]?.let { return it }

        var currentId = rootDocId
        val prefix = mutableListOf<String>()
        for (segment in dirSegments) {
            require(segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('/')) {
                "Invalid path segment: $segment"
            }
            prefix.add(segment)
            val key = prefix.joinToString("/")
            val cached = dirIdCache[key]
            if (cached != null) {
                currentId = cached
                continue
            }
            val existing = queryChildren(currentId).firstOrNull {
                it.displayName == segment && it.mimeType == Document.MIME_TYPE_DIR
            }
            val nextId = if (existing != null) {
                existing.documentId
            } else if (createIfMissing) {
                val createdUri = DocumentsContract.createDocument(
                    resolver,
                    docUri(currentId),
                    Document.MIME_TYPE_DIR,
                    segment
                ) ?: return null
                DocumentsContract.getDocumentId(createdUri)
            } else {
                return null
            }
            dirIdCache[key] = nextId
            currentId = nextId
        }
        return currentId
    }

    override fun readFile(segments: List<String>): ByteArray? {
        if (segments.isEmpty()) return null
        val parentId = resolveDirId(segments.dropLast(1), createIfMissing = false) ?: return null
        val target = queryChildren(parentId).firstOrNull {
            it.displayName == segments.last() && it.mimeType != Document.MIME_TYPE_DIR
        } ?: return null
        return runCatching {
            resolver.openInputStream(docUri(target.documentId))?.use { it.readBytes() }
        }.getOrNull()
    }

    override fun writeFile(
        segments: List<String>,
        bytes: ByteArray,
        appProperties: Map<String, String>
    ): String? {
        require(segments.isNotEmpty()) { "File path segments cannot be empty" }
        val parentId = resolveDirId(segments.dropLast(1), createIfMissing = true)
            ?: error("Unable to access folder in selected phone directory.")
        val fileName = segments.last()
        val existing = queryChildren(parentId).firstOrNull {
            it.displayName == fileName && it.mimeType != Document.MIME_TYPE_DIR
        }

        if (existing != null) {
            val existingUri = docUri(existing.documentId)
            val overwrote = runCatching {
                resolver.openOutputStream(existingUri, "wt")?.use { out ->
                    out.write(bytes)
                    out.flush()
                    true
                } ?: false
            }.getOrDefault(false)
            if (overwrote) {
                return segments.joinToString("/")
            }
            runCatching { DocumentsContract.deleteDocument(resolver, existingUri) }
        }

        val createdUri = DocumentsContract.createDocument(
            resolver,
            docUri(parentId),
            "application/octet-stream",
            fileName
        ) ?: error("Unable to create \"$fileName\" inside selected folder.")

        resolver.openOutputStream(createdUri, "w")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: error("Unable to write \"$fileName\" inside selected folder.")

        return segments.joinToString("/")
    }

    override fun deletePath(segments: List<String>): Boolean {
        if (segments.isEmpty()) return false
        val parentId = resolveDirId(segments.dropLast(1), createIfMissing = false) ?: return false
        val target = queryChildren(parentId).firstOrNull { it.displayName == segments.last() } ?: return false
        val deleted = runCatching {
            DocumentsContract.deleteDocument(resolver, docUri(target.documentId))
        }.getOrDefault(false)
        if (deleted) {
            val prefix = segments.joinToString("/")
            dirIdCache.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach {
                dirIdCache.remove(it)
            }
        }
        return deleted
    }

    override fun deleteByRemoteId(remoteId: String): Boolean {
        if (remoteId.isBlank()) return false
        val segments = remoteId.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        return deletePath(segments)
    }

    override fun ensureDirectory(segments: List<String>): String? =
        if (resolveDirId(segments, createIfMissing = true) != null) segments.joinToString("/") else null

    override fun listDirectory(segments: List<String>): List<EncrypzVaultEngine.BackendEntry> {
        val dirId = resolveDirId(segments, createIfMissing = false) ?: return emptyList()
        return queryChildren(dirId).map { child ->
            val isDir = child.mimeType == Document.MIME_TYPE_DIR
            if (isDir) {
                dirIdCache[(segments + child.displayName).joinToString("/")] = child.documentId
            }
            EncrypzVaultEngine.BackendEntry(
                name = child.displayName,
                isDirectory = isDir,
                remoteId = (segments + child.displayName).joinToString("/"),
                sizeBytes = child.sizeBytes
            )
        }
    }

    companion object {
        fun extractFolderDisplayName(context: Context, treeUri: Uri): String {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (!docId.isNullOrBlank()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(0)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
            return treeUri.lastPathSegment
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "Phone Vault"
        }
    }
}
