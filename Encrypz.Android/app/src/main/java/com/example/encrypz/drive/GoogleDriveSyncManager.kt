package com.example.encrypz.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.example.encrypz.auth.GoogleAuthConfig
import com.example.encrypz.auth.LoopbackGoogleOAuthServer
import com.example.encrypz.ui.vault.VaultFileEntry
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Handles creating the `Encrypz` folder in the user's Google Drive (if it does not exist)
 * and saving AES-256-GCM encrypted `.enc` files inside the `Encrypz` folder on Google Drive.
 */
object GoogleDriveSyncManager {

    const val VAULT_DRIVE_FOLDER_NAME = "Encrypz"
    private const val DRIVE_FILES_API = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    private const val GOOGLE_DRIVE_ROOT_URI = "content://com.google.android.apps.docs.storage/root"

    private const val PREFS_NAME = "encrypz_drive_sync_prefs"
    private const val KEY_VAULT_FOLDER_ID_PREFIX = "drive_encrypz_folder_id_v2_"

    sealed class DriveUploadResult {
        data class UploadedToDriveApi(
            val driveFileId: String,
            val folderId: String,
            val folderName: String = VAULT_DRIVE_FOLDER_NAME
        ) : DriveUploadResult()

        data class NeedsUserConsent(
            val consentIntent: Intent,
            val fileId: String
        ) : DriveUploadResult()

        data class UploadFailed(
            val fileId: String,
            val encryptedFileName: String,
            val reason: String
        ) : DriveUploadResult()
    }

    data class DrivePermissionResult(
        val granted: Boolean,
        val email: String,
        val folderId: String? = null,
        val refreshToken: String? = null,
        val consentIntent: Intent? = null,
        val authorizationIntent: PendingIntent? = null,
        val error: String? = null
    )

    data class RemoteDriveEncryptedFile(
        val driveFileId: String,
        val name: String,
        val encryptedBytes: ByteArray,
        val encryptedCreationDate: String = "",
        val driveCreatedTime: String = ""
    )

    sealed class DriveRemoteSyncResult {
        data class Success(
            val folderId: String,
            val downloadedFiles: List<RemoteDriveEncryptedFile>,
            val failedDownloads: Int = 0
        ) : DriveRemoteSyncResult()

        data class NeedsUserConsent(val consentIntent: Intent) : DriveRemoteSyncResult()
        data class Unavailable(val reason: String) : DriveRemoteSyncResult()
    }

    /**
     * Requests Google Drive permission during Login or Account Creation (if not already granted)
     * and immediately ensures the `Encrypz` folder exists in the user's Google Drive.
     */
    suspend fun requestDrivePermissionAndEnsureFolder(
        context: Context,
        accountEmail: String,
        fallbackRefreshTokenFromDb: String? = null,
        authorizationData: Intent? = null
    ): DrivePermissionResult = withContext(Dispatchers.IO) {
        val email = accountEmail.trim().lowercase()
        if (!email.contains("@")) {
            return@withContext DrivePermissionResult(false, email, error = "Choose a Google account first.")
        }
        try {
            val resolution = if (authorizationData != null) {
                val result = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(authorizationData)
                val token = result.accessToken
                if (token.isNullOrBlank()) TokenResolution.Failed("Google did not grant Drive access.")
                else TokenResolution.Token(token)
            } else {
                resolveDriveAccessToken(context, email, false)
            }
            when (resolution) {
                is TokenResolution.Token -> {
                    val folderId = try {
                        findOrCreateEncrypzVaultFolder(context, email, resolution.accessToken)
                    } catch (ex: Exception) {
                        if (!isAuthError(ex)) throw ex
                        GoogleAuthUtil.clearToken(context, resolution.accessToken)
                        val retry = resolveDriveAccessToken(context, email, false)
                        if (retry is TokenResolution.AuthorizationRequired) {
                            return@withContext DrivePermissionResult(false, email, authorizationIntent = retry.intent)
                        }
                        if (retry !is TokenResolution.Token) throw ex
                        findOrCreateEncrypzVaultFolder(context, email, retry.accessToken)
                    }
                    DrivePermissionResult(true, email, folderId = folderId)
                }
                is TokenResolution.AuthorizationRequired -> DrivePermissionResult(
                    false, email, authorizationIntent = resolution.intent
                )
                is TokenResolution.ConsentRequired -> DrivePermissionResult(false, email, consentIntent = resolution.intent)
                is TokenResolution.Failed -> DrivePermissionResult(false, email, error = resolution.reason)
            }
        } catch (ex: Exception) {
            DrivePermissionResult(false, email, error = driveErrorMessage(ex))
        }
    }

    // AuthorizationClient reuses the account's grant and renews tokens silently.
    // Only the login flow may launch a returned resolution; uploads never launch UI.
    private suspend fun resolveDriveAccessToken(
        context: Context,
        cleanEmail: String,
        allowInteractiveLoopback: Boolean,
        fallbackRefreshTokenFromDb: String? = null
    ): TokenResolution = withContext(Dispatchers.IO) {
        try {
            val request = AuthorizationRequest.builder()
                .setAccount(Account(cleanEmail, "com.google"))
                .setRequestedScopes(listOf(Scope(GoogleAuthConfig.SCOPE_DRIVE_FILE), Scope("https://www.googleapis.com/auth/userinfo.email")))
                .build()
            val result = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(request), 60, TimeUnit.SECONDS
            )
            if (result.hasResolution()) {
                val intent = result.pendingIntent
                return@withContext if (intent != null) TokenResolution.AuthorizationRequired(intent)
                    else TokenResolution.Failed("Google did not provide a Drive permission dialog.")
            }
            val token = result.accessToken
            if (token.isNullOrBlank()) TokenResolution.Failed("Google did not return a Drive access token.")
            else TokenResolution.Token(token)
        } catch (ex: Exception) {
            TokenResolution.Failed(driveErrorMessage(ex))
        }
    }

    suspend fun accountAccessToken(context: Context, email: String): String =
        when (val result = resolveDriveAccessToken(context, email, false)) {
            is TokenResolution.Token -> result.accessToken
            else -> throw IllegalStateException("Allow Google account permissions from the login screen or Security tab, then retry.")
        }

    private fun driveErrorMessage(ex: Exception): String {
        val cause = ex.cause ?: ex
        return if (cause is ApiException && cause.statusCode == 10) {
            "Google sign-in configuration error (10). Register this APK's package and signing SHA-1 in the Google Cloud Android OAuth client."
        } else {
            "Google Drive: ${cause.message ?: cause.javaClass.simpleName}"
        }
    }

    private sealed class TokenResolution {
        data class AuthorizationRequired(val intent: PendingIntent) : TokenResolution()
        data class Token(val accessToken: String) : TokenResolution()
        data class ConsentRequired(val intent: Intent) : TokenResolution()
        data class Failed(val reason: String) : TokenResolution()
    }

    /**
     * Checks if the `Encrypz` folder exists in the user's Google Drive, and creates a new folder
     * named `Encrypz` if it does not exist.
     */
    suspend fun ensureEncrypzFolderExists(
        context: Context,
        accountEmail: String,
        allowInteractiveAuth: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val cleanEmail = accountEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return@withContext null

        when (val resolution = resolveDriveAccessToken(context, cleanEmail, allowInteractiveAuth)) {
            is TokenResolution.Token -> {
                try {
                    findOrCreateEncrypzVaultFolder(context, cleanEmail, resolution.accessToken)
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Ensures the `Encrypz` folder exists in the user's Google Drive (creating it if it does not exist)
     * and directly uploads the AES-256-GCM encrypted [VaultFileEntry] (`.enc` payload) inside the `Encrypz` folder.
     */
    suspend fun uploadEncryptedFileToDrive(
        context: Context,
        accountEmail: String,
        file: VaultFileEntry,
        encryptedDriveName: String,
        encryptedCreationDate: String = ""
    ): DriveUploadResult = withContext(Dispatchers.IO) {
        val cleanEmail = accountEmail.trim()
        val safeEncryptedName = encryptedDriveName

        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext DriveUploadResult.UploadFailed(
                fileId = file.id,
                encryptedFileName = safeEncryptedName,
                reason = "No Google account email active"
            )
        }

        // Uploads NEVER prompt for permission â€” they reuse the permission granted at Login.
        var token = when (val res = resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false)) {
            is TokenResolution.Token -> res.accessToken
            is TokenResolution.AuthorizationRequired, is TokenResolution.ConsentRequired -> {
                return@withContext DriveUploadResult.UploadFailed(
                    fileId = file.id,
                    encryptedFileName = safeEncryptedName,
                    reason = "Google Drive permission not granted yet. It will be requested on your next login."
                )
            }
            is TokenResolution.Failed -> {
                return@withContext DriveUploadResult.UploadFailed(
                    fileId = file.id,
                    encryptedFileName = safeEncryptedName,
                    reason = res.reason
                )
            }
        }

        // Find or create the "Encrypz" folder in Google Drive and upload the encrypted .enc file inside it
        return@withContext try {
            val folderId = findOrCreateEncrypzVaultFolder(context, cleanEmail, token)
            val uploadedDriveId = uploadMultipartEncryptedBlob(
                accessToken = token,
                folderId = folderId,
                encryptedFileName = safeEncryptedName,
                encryptedPayload = file.encryptedPayload,
                ivHex = file.ivHex,
                encryptedCreationDate = encryptedCreationDate,
                existingDriveId = file.driveFileId?.takeIf { !it.startsWith("content://") }
            )


            DriveUploadResult.UploadedToDriveApi(
                driveFileId = uploadedDriveId,
                folderId = folderId,
                folderName = VAULT_DRIVE_FOLDER_NAME
            )
        } catch (firstEx: Exception) {
            if (!isAuthError(firstEx)) {
                return@withContext DriveUploadResult.UploadFailed(
                    fileId = file.id,
                    encryptedFileName = safeEncryptedName,
                    reason = firstEx.message ?: "Direct Drive API upload failed"
                )
            }
            // Access token expired/revoked: clear it and silently refresh (never prompt)
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (_: Exception) {
            }
            LoopbackGoogleOAuthServer.clearCachedAccessToken(context, cleanEmail)

            val retryRes = resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false)
            if (retryRes is TokenResolution.Token) {
                token = retryRes.accessToken
                try {
                    val folderId = findOrCreateEncrypzVaultFolder(context, cleanEmail, token)
                    val uploadedDriveId = uploadMultipartEncryptedBlob(
                        accessToken = token,
                        folderId = folderId,
                        encryptedFileName = safeEncryptedName,
                        encryptedPayload = file.encryptedPayload,
                        ivHex = file.ivHex,
                        encryptedCreationDate = encryptedCreationDate,
                        existingDriveId = file.driveFileId?.takeIf { !it.startsWith("content://") }
                    )

                    return@withContext DriveUploadResult.UploadedToDriveApi(
                        driveFileId = uploadedDriveId,
                        folderId = folderId,
                        folderName = VAULT_DRIVE_FOLDER_NAME
                    )
                } catch (retryEx: Exception) {
                    return@withContext DriveUploadResult.UploadFailed(
                        fileId = file.id,
                        encryptedFileName = safeEncryptedName,
                        reason = retryEx.message ?: "Direct Drive API upload failed"
                    )
                }
            }

            DriveUploadResult.UploadFailed(
                fileId = file.id,
                encryptedFileName = safeEncryptedName,
                reason = firstEx.message ?: "Direct Drive API upload failed"
            )
        }
    }

    /**
     * Ensures the `Encrypz` folder exists in the user's Google Drive and downloads any encrypted (`.enc`)
     * files stored inside `Encrypz` that are not yet present in the local vault.
     */
    suspend fun syncEncryptedFilesFromDrive(
        context: Context,
        accountEmail: String,
        existingDriveIds: Set<String>,
        existingFileNames: Set<String>
    ): DriveRemoteSyncResult = withContext(Dispatchers.IO) {
        val cleanEmail = accountEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext DriveRemoteSyncResult.Unavailable("Connect your Google account first")
        }

        val token = when (val res = resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false)) {
            is TokenResolution.Token -> res.accessToken
            is TokenResolution.AuthorizationRequired, is TokenResolution.ConsentRequired -> {
                return@withContext DriveRemoteSyncResult.Unavailable(
                    "Google Drive permission not granted yet. It will be requested on your next login."
                )
            }
            is TokenResolution.Failed -> {
                return@withContext DriveRemoteSyncResult.Unavailable(res.reason)
            }
        }

        return@withContext try {
            // Reinstallations may encounter more than one app-created folder, including the old name.
            val folders = listDriveFiles("mimeType = 'application/vnd.google-apps.folder' and " +
                "(name = '$VAULT_DRIVE_FOLDER_NAME' or name = 'Encrypz Vault') and trashed = false", token)
            val folderIds = folders.map { it.getString("id") }.distinct()
            val downloaded = mutableListOf<RemoteDriveEncryptedFile>()
            val seen = existingDriveIds.toMutableSet()
            var failures = 0
            for (folderId in folderIds) {
                for (item in listDriveFiles("'$folderId' in parents and trashed = false", token)) {
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isBlank() || !name.endsWith(".enc", ignoreCase = true) || !seen.add(id)) continue
                    // Identical filenames are valid distinct files. Deduplicate only by Drive ID.
                    val bytes = downloadDriveFileBytes(id, token)
                    if (bytes != null && bytes.size >= 28) {
                        downloaded.add(RemoteDriveEncryptedFile(id, name, bytes,
                            item.optJSONObject("appProperties")?.optString("encCreated").orEmpty(),
                            item.optString("createdTime")))
                    } else failures++
                }
            }
            DriveRemoteSyncResult.Success(folderIds.firstOrNull().orEmpty(), downloaded, failures)
        } catch (ex: Exception) {
            DriveRemoteSyncResult.Unavailable(ex.message ?: "Unable to sync Google Drive 'Encrypz' folder")
        }
    }

    private fun listDriveFiles(query: String, accessToken: String): List<JSONObject> =
        DrivePages.collect { pageToken ->
            val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val url = "$DRIVE_FILES_API?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&spaces=drive&fields=nextPageToken,files(id,name,size,createdTime,appProperties)&pageSize=100$page"
            val json = executeJsonGet(url, accessToken)
            val array = json.optJSONArray("files") ?: JSONArray()
            DrivePages.Page((0 until array.length()).map { array.getJSONObject(it) },
                json.optString("nextPageToken").takeIf { it.isNotBlank() })
        }

    /**
     * Deletes an encrypted .enc file from Google Drive when permanently deleted in Encrypz.
     */
    suspend fun deleteEncryptedFileFromDrive(
        context: Context,
        accountEmail: String,
        driveFileId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (driveFileId.isNullOrBlank()) return@withContext false

        if (driveFileId.startsWith("content://")) {
            return@withContext try {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(driveFileId))
            } catch (_: Exception) {
                false
            }
        }

        val cleanEmail = accountEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return@withContext false

        return@withContext try {
            val tokenRes = resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false)
            val token = (tokenRes as? TokenResolution.Token)?.accessToken ?: return@withContext false
            val encodedId = URLEncoder.encode(driveFileId, "UTF-8")
            val conn = (URL("$DRIVE_FILES_API/$encodedId").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299 || code == 404
        } catch (_: Exception) {
            false
        }
    }

    fun createGoogleDriveSaveDocumentIntent(encryptedFileName: String): Intent {
        val safeName = if (encryptedFileName.endsWith(".enc")) {
            encryptedFileName
        } else {
            "$encryptedFileName.enc"
        }
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, safeName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse(GOOGLE_DRIVE_ROOT_URI)
                )
            }
        }
    }

    fun createDecryptedExportDocumentIntent(cleanFileName: String): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, cleanFileName)
        }
    }

    suspend fun writeBytesToUri(
        context: Context,
        targetUri: Uri,
        bytes: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: context.contentResolver.openOutputStream(targetUri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Searches the user's Google Drive for a folder named `Encrypz` (`trashed = false`).
     * If the `Encrypz` folder does not exist, creates a new folder named `Encrypz` in Google Drive
     * and returns its Google Drive folder ID.
     */
    private val folderLock = Any()

    private fun findOrCreateEncrypzVaultFolder(
        context: Context,
        email: String,
        accessToken: String
    ): String = synchronized(folderLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedFolderId = prefs.getString("$KEY_VAULT_FOLDER_ID_PREFIX$email", null)
        if (!cachedFolderId.isNullOrBlank()) {
            try {
                val encodedId = URLEncoder.encode(cachedFolderId, "UTF-8")
                val meta = executeJsonGet("$DRIVE_FILES_API/$encodedId?fields=id,name,trashed", accessToken)
                if (!meta.optBoolean("trashed", false) &&
                    meta.optString("id").isNotBlank() &&
                    meta.optString("name") == VAULT_DRIVE_FOLDER_NAME
                ) {
                    return cachedFolderId
                }
            } catch (_: Exception) {
                // Folder was deleted or token lacked access to cached ID; search/create below
            }
        }

        // 1. Check if folder named "Encrypz" already exists in Google Drive
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$VAULT_DRIVE_FOLDER_NAME' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$DRIVE_FILES_API?q=$encodedQuery&spaces=drive&fields=files(id,name)&pageSize=10"
        val searchJson = executeJsonGet(searchUrl, accessToken)
        val files = searchJson.optJSONArray("files")
        if (files != null && files.length() > 0) {
            val foundId = files.getJSONObject(0).optString("id")
            if (foundId.isNotBlank()) {
                prefs.edit().putString("$KEY_VAULT_FOLDER_ID_PREFIX$email", foundId).apply()
                return foundId
            }
        }

        // 2. Folder "Encrypz" does not exist in Google Drive -> create new folder named "Encrypz"
        val createMeta = JSONObject().apply {
            put("name", VAULT_DRIVE_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().put("root"))
            put("description", "Encrypz Zero-Knowledge AES-256-GCM Encrypted Vault")
        }
        val conn = (URL("$DRIVE_FILES_API?fields=id,name").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        conn.outputStream.use { out ->
            out.write(createMeta.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) {
            throw IOException("Failed to create '$VAULT_DRIVE_FOLDER_NAME' folder in Google Drive ($code): $body")
        }
        val createdId = JSONObject(body).getString("id")
        prefs.edit().putString("$KEY_VAULT_FOLDER_ID_PREFIX$email", createdId).apply()
        return createdId
    }

    private fun uploadMultipartEncryptedBlob(
        accessToken: String,
        folderId: String,
        encryptedFileName: String,
        encryptedPayload: ByteArray,
        ivHex: String,
        existingDriveId: String?,
        encryptedCreationDate: String
    ): String {
        val boundary = "===encrypz_vault_boundary_${System.currentTimeMillis()}==="
        val metadataJson = JSONObject().apply {
            put("name", encryptedFileName)
            if (encryptedCreationDate.isNotBlank()) put("appProperties", JSONObject().put("encCreated", encryptedCreationDate))
            if (existingDriveId.isNullOrBlank()) {
                put("parents", JSONArray().put(folderId))
            }
            put("mimeType", "application/octet-stream")
            put(
                "description",
                "Encrypz AES-256-GCM Encrypted File inside '$VAULT_DRIVE_FOLDER_NAME' folder (IV: 0x$ivHex)"
            )
        }

        val url = if (!existingDriveId.isNullOrBlank()) {
            val encodedId = URLEncoder.encode(existingDriveId, "UTF-8")
            "$DRIVE_UPLOAD_API/$encodedId?uploadType=multipart&fields=id,name"
        } else {
            "$DRIVE_UPLOAD_API?uploadType=multipart&fields=id,name"
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (!existingDriveId.isNullOrBlank()) "PATCH" else "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connectTimeout = 25_000
            readTimeout = 25_000
        }

        val multipartBody = ByteArrayOutputStream()
        val lineEnd = "\r\n"
        multipartBody.write("--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
        multipartBody.write("Content-Type: application/json; charset=UTF-8$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
        multipartBody.write(metadataJson.toString().toByteArray(Charsets.UTF_8))
        multipartBody.write("$lineEnd--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
        multipartBody.write("Content-Type: application/octet-stream$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
        multipartBody.write(encryptedPayload)
        multipartBody.write("$lineEnd--$boundary--$lineEnd".toByteArray(Charsets.UTF_8))

        conn.outputStream.use { out ->
            out.write(multipartBody.toByteArray())
            out.flush()
        }

        val code = conn.responseCode
        val responseText = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) {
            throw IOException("Google Drive upload HTTP $code: $responseText")
        }
        return JSONObject(responseText).getString("id")
    }

    private fun executeJsonGet(url: String, accessToken: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val code = conn.responseCode
        val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) {
            throw IOException("Google Drive GET HTTP $code: $body")
        }
        return JSONObject(body)
    }

    private fun downloadDriveFileBytes(fileId: String, accessToken: String): ByteArray? {
        val encodedId = URLEncoder.encode(fileId, "UTF-8")
        val conn = (URL("$DRIVE_FILES_API/$encodedId?alt=media").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        val bytes = if (code in 200..299) {
            conn.inputStream?.use { it.readBytes() }
        } else {
            null
        }
        conn.disconnect()
        return bytes
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** True if the Drive API rejected the access token (expired / revoked). */
    private fun isAuthError(ex: Exception): Boolean {
        val msg = ex.message ?: return false
        return msg.contains("HTTP 401") || msg.contains("(401)")
    }
}
