package com.example.encrypz.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.example.encrypz.auth.GoogleAuthConfig
import com.example.encrypz.auth.LoopbackGoogleOAuthServer
import com.example.encrypz.data.MongoAtlasClient
import com.example.encrypz.ui.vault.VaultFileEntry
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
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
        val consentIntent: Intent? = null
    )

    data class RemoteDriveEncryptedFile(
        val driveFileId: String,
        val name: String,
        val encryptedBytes: ByteArray
    )

    sealed class DriveRemoteSyncResult {
        data class Success(
            val folderId: String,
            val downloadedFiles: List<RemoteDriveEncryptedFile>
        ) : DriveRemoteSyncResult()

        data class NeedsUserConsent(val consentIntent: Intent) : DriveRemoteSyncResult()
        data class Unavailable(val reason: String) : DriveRemoteSyncResult()
    }

    /**
     * Checks whether the account already has a cached or refreshable Google Drive OAuth token.
     */
    fun hasAuthorizedDriveAccess(
        context: Context,
        accountEmail: String,
        fallbackRefreshTokenFromDb: String? = null
    ): Boolean {
        val cleanEmail = accountEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return false
        return LoopbackGoogleOAuthServer.hasCachedOrRefreshToken(
            context = context,
            email = cleanEmail,
            fallbackRefreshTokenFromDb = fallbackRefreshTokenFromDb
        )
    }

    /**
     * Requests Google Drive permission during Login or Account Creation (if not already granted)
     * and immediately ensures the `Encrypz` folder exists in the user's Google Drive.
     */
    suspend fun requestDrivePermissionAndEnsureFolder(
        context: Context,
        accountEmail: String,
        fallbackRefreshTokenFromDb: String? = null
    ): DrivePermissionResult = withContext(Dispatchers.IO) {
        val cleanEmail = accountEmail.trim()

        // 1. Silently reuse an already-granted permission (cached / refresh token / GMS token).
        //    Only if nothing is available do we show the Google consent screen (once, at login).
        if (cleanEmail.contains("@")) {
            val silent = resolveDriveAccessToken(
                context = context,
                cleanEmail = cleanEmail,
                allowInteractiveLoopback = false,
                fallbackRefreshTokenFromDb = fallbackRefreshTokenFromDb
            )
            if (silent is TokenResolution.ConsentRequired) {
                return@withContext DrivePermissionResult(
                    granted = false, email = cleanEmail, consentIntent = silent.intent
                )
            }
            if (silent is TokenResolution.Token) {
                val folderId = try {
                    findOrCreateEncrypzVaultFolder(context, cleanEmail, silent.accessToken)
                } catch (ex: Exception) {
                    if (isAuthError(ex)) {
                        LoopbackGoogleOAuthServer.clearCachedAccessToken(context, cleanEmail)
                        try { GoogleAuthUtil.clearToken(context, silent.accessToken) } catch (_: Exception) { }
                        val refreshed = resolveDriveAccessToken(context, cleanEmail, false)
                        if (refreshed is TokenResolution.ConsentRequired) {
                            return@withContext DrivePermissionResult(
                                granted = false, email = cleanEmail, consentIntent = refreshed.intent
                            )
                        }
                        if (refreshed is TokenResolution.Token) {
                            return@withContext DrivePermissionResult(
                                granted = true, email = cleanEmail,
                                folderId = try {
                                    findOrCreateEncrypzVaultFolder(context, cleanEmail, refreshed.accessToken)
                                } catch (_: Exception) { null }
                            )
                        }
                        null
                    } else {
                        // Network/transient error: permission is still granted, folder will be created on upload
                        ""
                    }
                }
                if (folderId != null) {
                    return@withContext DrivePermissionResult(
                        granted = true,
                        email = cleanEmail,
                        folderId = folderId.ifBlank { null },
                        refreshToken = LoopbackGoogleOAuthServer.getSavedRefreshToken(context, cleanEmail)
                    )
                }
            }
        }

        return@withContext DrivePermissionResult(
            granted = false,
            email = cleanEmail
        )
    }

    /**
     * Resolves a valid Google Drive OAuth 2.0 access token for [cleanEmail]:
     * 1. Cached or refreshed OAuth token from [LoopbackGoogleOAuthServer] / MongoDB refresh token.
     * 2. Native Android [GoogleAuthUtil.getToken] if already granted without requiring GMS consent.
     * 3. Loopback OAuth 2.0 flow (`http://localhost:5207/api/Auth/google-callback`) using
     *    [GoogleAuthConfig.CLIENT_ID] and [GoogleAuthConfig.CLIENT_SECRET].
     */
    private suspend fun resolveDriveAccessToken(
        context: Context,
        cleanEmail: String,
        allowInteractiveLoopback: Boolean,
        fallbackRefreshTokenFromDb: String? = null
    ): TokenResolution = withContext(Dispatchers.IO) {
        // 1. Check cached / refreshable OAuth token first
        val dbRefreshToken = fallbackRefreshTokenFromDb?.takeIf { it.isNotBlank() }
            ?: if (LoopbackGoogleOAuthServer.getSavedRefreshToken(context, cleanEmail).isNullOrBlank()) {
                try {
                    MongoAtlasClient.findUserVaultRecord(cleanEmail)?.googleRefreshToken
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        val cachedOrRefreshed = LoopbackGoogleOAuthServer.getValidAccessTokenOrRefresh(
            context = context,
            email = cleanEmail,
            fallbackRefreshTokenFromDb = dbRefreshToken
        )
        if (!cachedOrRefreshed.isNullOrBlank()) {
            return@withContext TokenResolution.Token(cachedOrRefreshed)
        }

        // 2. Try Google Play Services GoogleAuthUtil.getToken if already authorized (silent)
        var recoverableIntent: Intent? = null
        try {
            val scope = "oauth2:${GoogleAuthConfig.SCOPE_DRIVE_FILE}"
            val gmsToken = GoogleAuthUtil.getToken(context, Account(cleanEmail, "com.google"), scope)
            if (!gmsToken.isNullOrBlank()) {
                // Cache it so the app remembers Drive permission was granted (no re-prompt on upload)
                LoopbackGoogleOAuthServer.saveTokenBundle(
                    context,
                    cleanEmail,
                    LoopbackGoogleOAuthServer.OAuthTokenBundle(
                        accessToken = gmsToken,
                        refreshToken = null,
                        email = cleanEmail,
                        expiresInSeconds = 3000L
                    )
                )
                return@withContext TokenResolution.Token(gmsToken)
            }
        } catch (recoverable: UserRecoverableAuthException) {
            recoverableIntent = recoverable.intent
        } catch (_: GoogleAuthException) {
            // Will use Web OAuth Loopback flow below
        } catch (_: Exception) {
            // Will use Web OAuth Loopback flow below
        }

        // 3. Use the configured Web OAuth Client ID & Secret via Loopback OAuth flow
        if (allowInteractiveLoopback && GoogleAuthConfig.CLIENT_ID.isNotBlank() && GoogleAuthConfig.CLIENT_SECRET.isNotBlank()) {
            val bundle = LoopbackGoogleOAuthServer.authorizeViaLoopbackBrowser(
                context = context,
                loginHintEmail = cleanEmail
            )
            if (bundle != null && bundle.accessToken.isNotBlank()) {
                return@withContext TokenResolution.Token(bundle.accessToken)
            }
        }

        // Return native consent to the login screen; uploads never launch it.
        if (recoverableIntent != null) {
            return@withContext TokenResolution.ConsentRequired(recoverableIntent)
        }

        return@withContext TokenResolution.Failed("Google Drive permission is required to save files to the 'Encrypz' folder")
    }

    private sealed class TokenResolution {
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
        file: VaultFileEntry
    ): DriveUploadResult = withContext(Dispatchers.IO) {
        val cleanEmail = accountEmail.trim()
        val safeEncryptedName = if (file.name.endsWith(".enc")) file.name else "${file.name}.enc"

        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return@withContext DriveUploadResult.UploadFailed(
                fileId = file.id,
                encryptedFileName = safeEncryptedName,
                reason = "No Google account email active"
            )
        }

        // Uploads NEVER prompt for permission — they reuse the permission granted at Login.
        var token = when (val res = resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false)) {
            is TokenResolution.Token -> res.accessToken
            is TokenResolution.ConsentRequired -> {
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
                existingDriveId = file.driveFileId?.takeIf { !it.startsWith("content://") }
            )

            recordEncryptedFileInMongo(file, cleanEmail, safeEncryptedName, uploadedDriveId)

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
                        existingDriveId = file.driveFileId?.takeIf { !it.startsWith("content://") }
                    )
                    recordEncryptedFileInMongo(file, cleanEmail, safeEncryptedName, uploadedDriveId)
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

    private fun recordEncryptedFileInMongo(
        file: VaultFileEntry,
        cleanEmail: String,
        safeEncryptedName: String,
        uploadedDriveId: String
    ) {
        try {
            MongoAtlasClient.upsertEncryptedFileMetadata(
                fileId = file.id,
                email = cleanEmail,
                encryptedFileName = safeEncryptedName,
                driveFileId = uploadedDriveId,
                driveFolderName = VAULT_DRIVE_FOLDER_NAME,
                ivHex = file.ivHex,
                sizeBytes = file.sizeBytes,
                uploadedAt = file.uploadedAt,
                isDeleted = file.isDeleted
            )
        } catch (_: Exception) {
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
            is TokenResolution.ConsentRequired -> {
                return@withContext DriveRemoteSyncResult.Unavailable(
                    "Google Drive permission not granted yet. It will be requested on your next login."
                )
            }
            is TokenResolution.Failed -> {
                return@withContext DriveRemoteSyncResult.Unavailable(res.reason)
            }
        }

        return@withContext try {
            val folderId = findOrCreateEncrypzVaultFolder(context, cleanEmail, token)
            val query = "'$folderId' in parents and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val listUrl = "$DRIVE_FILES_API?q=$encodedQuery&spaces=drive&fields=files(id,name,size)&pageSize=100"

            val responseJson = executeJsonGet(listUrl, token)
            val filesArray = responseJson.optJSONArray("files") ?: JSONArray()
            val downloaded = mutableListOf<RemoteDriveEncryptedFile>()

            for (i in 0 until filesArray.length()) {
                val item = filesArray.getJSONObject(i)
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || !name.endsWith(".enc")) continue
                if (id in existingDriveIds || name in existingFileNames) continue

                val bytes = downloadDriveFileBytes(id, token)
                if (bytes != null && bytes.size > 28) {
                    downloaded.add(
                        RemoteDriveEncryptedFile(
                            driveFileId = id,
                            name = name,
                            encryptedBytes = bytes
                        )
                    )
                }
            }
            DriveRemoteSyncResult.Success(
                folderId = folderId,
                downloadedFiles = downloaded
            )
        } catch (ex: Exception) {
            DriveRemoteSyncResult.Unavailable(ex.message ?: "Unable to sync Google Drive 'Encrypz' folder")
        }
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
    private fun findOrCreateEncrypzVaultFolder(
        context: Context,
        email: String,
        accessToken: String
    ): String {
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
        existingDriveId: String?
    ): String {
        val boundary = "===encrypz_vault_boundary_${System.currentTimeMillis()}==="
        val metadataJson = JSONObject().apply {
            put("name", encryptedFileName)
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
