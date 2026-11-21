package com.encrypz.privacy.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.encrypz.privacy.auth.GoogleAuthConfig
import com.encrypz.privacy.auth.LoopbackGoogleOAuthServer
import com.encrypz.privacy.ui.vault.VaultFileEntry
import com.encrypz.privacy.vault.DiscoveredDriveVault
import com.encrypz.privacy.vault.VaultKeyRecord
import com.encrypz.privacy.crypto.vault.VaultMasterkey
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
    const val MASTERKEY_FILE_NAME = "masterkey.encrypz"
    private const val LEGACY_ACCOUNT_RECORD_FILE_NAME = "encrypz_account_v2.json"
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
            val reason: String,
            val retryable: Boolean = true
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

    sealed class DriveFolderPrepResult {
        data class Ready(val folderId: String) : DriveFolderPrepResult()
        data class AuthorizationRequired(val pendingIntent: PendingIntent) : DriveFolderPrepResult()
        data class ConsentRequired(val consentIntent: Intent) : DriveFolderPrepResult()
        data class Failed(val reason: String) : DriveFolderPrepResult()
    }

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

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    private fun authorizationClient(context: Context) =
        findActivity(context)?.let { Identity.getAuthorizationClient(it) }
            ?: Identity.getAuthorizationClient(context)

    private fun canUseBrowserOAuth(): Boolean =
        GoogleAuthConfig.CLIENT_ID.isNotBlank() && GoogleAuthConfig.CLIENT_SECRET.isNotBlank()

    private suspend fun resolveViaBrowserOAuth(
        context: Context,
        email: String?,
        forcePrompt: Boolean
    ): TokenResolution {
        return when (
            val oauth = LoopbackGoogleOAuthServer.authorizeViaLoopbackBrowserWithStatus(
                context = context,
                loginHintEmail = email?.takeIf { it.contains("@") },
                forcePrompt = forcePrompt
            )
        ) {
            is LoopbackGoogleOAuthServer.OAuthLaunchResult.Success -> {
                TokenResolution.Token(oauth.bundle.accessToken)
            }
            is LoopbackGoogleOAuthServer.OAuthLaunchResult.Failure -> {
                TokenResolution.Failed(oauth.reason)
            }
        }
    }

    /**
     * Requests Google Drive permission during Login or Account Creation (if not already granted)
     * and immediately ensures the `Encrypz` folder exists in the user's Google Drive.
     */
    suspend fun requestDrivePermissionAndEnsureFolder(
        context: Context,
        accountEmail: String,
        fallbackRefreshToken: String? = null,
        authorizationData: Intent? = null,
        forceBrowserOAuth: Boolean = false
    ): DrivePermissionResult = withContext(Dispatchers.IO) {
        val email = accountEmail.trim().lowercase()
        if (!forceBrowserOAuth && !email.contains("@")) {
            return@withContext DrivePermissionResult(false, email, error = "Choose a Google account first.")
        }
        try {
            val resolution = when {
                forceBrowserOAuth -> {
                    resolveViaBrowserOAuth(
                        context = context,
                        email = email.takeIf { it.contains("@") },
                        forcePrompt = true
                    )
                }
                authorizationData != null -> {
                    try {
                        val result = authorizationClient(context)
                            .getAuthorizationResultFromIntent(authorizationData)
                        val token = result.accessToken
                        if (!token.isNullOrBlank()) {
                            TokenResolution.Token(token)
                        } else if (canUseBrowserOAuth()) {
                            resolveViaBrowserOAuth(context, email, forcePrompt = false)
                        } else {
                            TokenResolution.Failed("Google did not grant Drive access.")
                        }
                    } catch (ex: Exception) {
                        if (canUseBrowserOAuth()) {
                            resolveViaBrowserOAuth(context, email, forcePrompt = false)
                        } else {
                            TokenResolution.Failed(driveErrorMessage(ex))
                        }
                    }
                }
                else -> {
                    resolveDriveAccessToken(
                        context = context,
                        cleanEmail = email,
                        allowInteractiveLoopback = true,
                        fallbackRefreshToken = fallbackRefreshToken
                    )
                }
            }
            when (resolution) {
                is TokenResolution.Token -> {
                    val resolvedEmail = email.ifBlank {
                        GoogleAuthConfig.getSavedGoogleEmail(context)?.trim()?.lowercase().orEmpty()
                    }
                    val folderEmail = resolvedEmail.ifBlank { "default" }
                    val folderId = try {
                        findOrCreateEncrypzVaultFolder(context, folderEmail, resolution.accessToken)
                    } catch (ex: Exception) {
                        if (!isAuthError(ex)) throw ex
                        try { GoogleAuthUtil.clearToken(context, resolution.accessToken) } catch (_: Exception) {}
                        LoopbackGoogleOAuthServer.clearCachedAccessToken(context, folderEmail)
                        val retry = resolveDriveAccessToken(
                            context,
                            folderEmail,
                            allowInteractiveLoopback = true,
                            fallbackRefreshToken = fallbackRefreshToken
                        )
                        if (retry is TokenResolution.AuthorizationRequired) {
                            return@withContext DrivePermissionResult(false, resolvedEmail, authorizationIntent = retry.intent)
                        }
                        if (retry !is TokenResolution.Token) throw ex
                        findOrCreateEncrypzVaultFolder(context, folderEmail, retry.accessToken)
                    }
                    DrivePermissionResult(
                        granted = true,
                        email = resolvedEmail,
                        folderId = folderId,
                        refreshToken = LoopbackGoogleOAuthServer.getSavedRefreshToken(context, folderEmail)
                    )
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
        fallbackRefreshToken: String? = null
    ): TokenResolution = withContext(Dispatchers.IO) {
        val cachedOrRefreshed = LoopbackGoogleOAuthServer.getValidAccessTokenOrRefresh(
            context = context,
            email = cleanEmail,
            fallbackRefreshToken = fallbackRefreshToken
        )
        if (!cachedOrRefreshed.isNullOrBlank()) {
            return@withContext TokenResolution.Token(cachedOrRefreshed)
        }

        try {
            val request = AuthorizationRequest.builder()
                .setAccount(Account(cleanEmail, "com.google"))
                .setRequestedScopes(listOf(Scope(GoogleAuthConfig.SCOPE_DRIVE_FILE), Scope("https://www.googleapis.com/auth/userinfo.email")))
                .build()
            val result = Tasks.await(
                authorizationClient(context).authorize(request), 60, TimeUnit.SECONDS
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
            if (allowInteractiveLoopback && canUseBrowserOAuth()) {
                return@withContext resolveViaBrowserOAuth(context, cleanEmail, forcePrompt = false)
            }
            TokenResolution.Failed(driveErrorMessage(ex))
        }
    }

    fun findDriveAccountRecord(context: Context, accountEmail: String): VaultKeyRecord? {
        val cleanEmail = accountEmail.trim().lowercase()
        if (!cleanEmail.contains("@")) return null
        val token = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            (resolveDriveAccessToken(context, cleanEmail, allowInteractiveLoopback = false) as? TokenResolution.Token)?.accessToken
        } ?: return null
        return try {
            val folders = listDriveFiles("mimeType = 'application/vnd.google-apps.folder' and " +
                "(name = '$VAULT_DRIVE_FOLDER_NAME' or name = 'Encrypz Vault') and trashed = false", token)
            for (folder in folders) {
                val folderId = folder.optString("id")
                if (folderId.isBlank()) continue
                val recordFile = listDriveFiles(
                    "'$folderId' in parents and trashed = false and " +
                        "(name = '$MASTERKEY_FILE_NAME' or name = '$LEGACY_ACCOUNT_RECORD_FILE_NAME')",
                    token
                ).firstOrNull() ?: continue
                val bytes = downloadDriveFileBytes(recordFile.optString("id"), token) ?: continue
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                val saltHex = json.optString("passwordSaltHex")
                val verifierHex = json.optString("passwordVerifierHex")
                val wrappedKeyHex = json.optString("wrappedVaultKeyHex")
                if (saltHex.isNotBlank() && wrappedKeyHex.isNotBlank()) {
                    return VaultKeyRecord(
                        email = json.optString("email", cleanEmail).ifBlank { cleanEmail },
                        passwordSaltHex = saltHex,
                        passwordVerifierHex = verifierHex,
                        wrappedVaultKeyHex = wrappedKeyHex,
                        driveFolderId = folderId,
                        updatedAt = json.optString("updatedAt", ""),
                        credentialVersion = json.optInt("credentialVersion", 2)
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun saveDriveAccountRecord(
        context: Context,
        accountEmail: String,
        record: VaultKeyRecord
    ): Boolean {
        val cleanEmail = accountEmail.trim().lowercase()
        if (!cleanEmail.contains("@")) return false
        val token = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            (resolveDriveAccessToken(
                context,
                cleanEmail,
                allowInteractiveLoopback = false,
                fallbackRefreshToken = record.googleRefreshToken
            ) as? TokenResolution.Token)?.accessToken
        } ?: return false
        return try {
            val folderId = findOrCreateEncrypzVaultFolder(context, cleanEmail, token)
            val existingFileId = listDriveFiles(
                "'$folderId' in parents and trashed = false and " +
                    "(name = '$MASTERKEY_FILE_NAME' or name = '$LEGACY_ACCOUNT_RECORD_FILE_NAME')",
                token
            ).firstOrNull()?.optString("id")
            val payloadJson = JSONObject().apply {
                put("format", "encrypz-masterkey-v2")
                put("kdf", "PBKDF2-HMAC-SHA256")
                put("iterations", 600000)
                put("email", cleanEmail)
                put("passwordSaltHex", record.passwordSaltHex)
                put("passwordVerifierHex", record.passwordVerifierHex)
                put("wrappedVaultKeyHex", record.wrappedVaultKeyHex)
                put("credentialVersion", record.credentialVersion)
                put("updatedAt", record.updatedAt)
            }
            uploadMultipartEncryptedBlob(
                accessToken = token,
                folderId = folderId,
                encryptedFileName = MASTERKEY_FILE_NAME,
                encryptedPayload = payloadJson.toString().toByteArray(Charsets.UTF_8),
                ivHex = "account_metadata",
                existingDriveId = existingFileId,
                encryptedCreationDate = ""
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getDriveAccessToken(context: Context, email: String, allowInteractive: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            (resolveDriveAccessToken(context, email.trim().lowercase(), allowInteractive) as? TokenResolution.Token)?.accessToken
        }

    suspend fun prepareDriveVaultRootFolder(
        context: Context,
        accountEmail: String,
        vaultName: String
    ): DriveFolderPrepResult = withContext(Dispatchers.IO) {
        val email = accountEmail.trim().lowercase()
        if (!email.contains("@")) {
            return@withContext DriveFolderPrepResult.Failed("Choose a Google account first.")
        }
        val folderName = vaultName.trim().ifBlank { "Encrypz Vault" }
        try {
            when (val res = resolveDriveAccessToken(context, email, allowInteractiveLoopback = true)) {
                is TokenResolution.Token -> {
                    val folderId = try {
                        createSubfolderInParent(res.accessToken, "root", folderName)
                    } catch (ex: Exception) {
                        if (!isAuthError(ex)) throw ex
                        try { GoogleAuthUtil.clearToken(context, res.accessToken) } catch (_: Exception) {}
                        LoopbackGoogleOAuthServer.clearCachedAccessToken(context, email)
                        when (val retry = resolveDriveAccessToken(context, email, allowInteractiveLoopback = true)) {
                            is TokenResolution.Token -> createSubfolderInParent(retry.accessToken, "root", folderName)
                            is TokenResolution.AuthorizationRequired ->
                                return@withContext DriveFolderPrepResult.AuthorizationRequired(retry.intent)
                            is TokenResolution.ConsentRequired ->
                                return@withContext DriveFolderPrepResult.ConsentRequired(retry.intent)
                            is TokenResolution.Failed ->
                                return@withContext DriveFolderPrepResult.Failed(retry.reason)
                        }
                    }
                    DriveFolderPrepResult.Ready(folderId)
                }
                is TokenResolution.AuthorizationRequired ->
                    DriveFolderPrepResult.AuthorizationRequired(res.intent)
                is TokenResolution.ConsentRequired ->
                    DriveFolderPrepResult.ConsentRequired(res.intent)
                is TokenResolution.Failed ->
                    DriveFolderPrepResult.Failed(res.reason)
            }
        } catch (ex: Exception) {
            DriveFolderPrepResult.Failed(driveErrorMessage(ex))
        }
    }

    suspend fun createDriveVaultRootFolder(
        context: Context,
        accountEmail: String,
        vaultName: String
    ): String? =
        (prepareDriveVaultRootFolder(context, accountEmail, vaultName) as? DriveFolderPrepResult.Ready)?.folderId

    suspend fun discoverEncrypzVaults(
        context: Context,
        accountEmail: String
    ): List<DiscoveredDriveVault> = withContext(Dispatchers.IO) {
        val token = getDriveAccessToken(context, accountEmail, allowInteractive = false) ?: return@withContext emptyList()
        val discovered = mutableListOf<DiscoveredDriveVault>()
        val seenFolderIds = mutableSetOf<String>()

        // 1. Search for Encrypz Vault v2 folders (vault.encrypz)
        val configFiles = runCatching {
            listDriveFilesWithParents("name = 'vault.encrypz' and trashed = false", token)
        }.getOrDefault(emptyList())
        for (file in configFiles) {
            val fileId = file.optString("id")
            val parents = file.optJSONArray("parents")
            val parentId = parents?.optString(0).orEmpty()
            if (fileId.isBlank() || parentId.isBlank() || !seenFolderIds.add(parentId)) continue
            val parentMeta = runCatching {
                executeJsonGet("$DRIVE_FILES_API/${URLEncoder.encode(parentId, "UTF-8")}?fields=id,name,trashed", token)
            }.getOrNull() ?: continue
            if (parentMeta.optBoolean("trashed", false)) continue
            val manifestJson = downloadDriveFileBytes(fileId, token)?.toString(Charsets.UTF_8).orEmpty()
            val config = VaultMasterkey.parseVaultConfigUnverified(manifestJson)
            discovered.add(
                DiscoveredDriveVault(
                    folderId = parentId,
                    folderName = parentMeta.optString("name", "Encrypz Vault"),
                    formatVersion = config?.format ?: 2,
                    vaultId = config?.vaultId.orEmpty()
                )
            )
        }

        // 2. Also surface legacy Encrypz vaults if any exist
        val legacyFolders = runCatching {
            listDriveFiles("mimeType = 'application/vnd.google-apps.folder' and " +
                "(name = '$VAULT_DRIVE_FOLDER_NAME' or name = 'Encrypz Vault') and trashed = false", token)
        }.getOrDefault(emptyList())
        for (folder in legacyFolders) {
            val id = folder.optString("id")
            if (id.isBlank() || !seenFolderIds.add(id)) continue
            discovered.add(
                DiscoveredDriveVault(
                    folderId = id,
                    folderName = folder.optString("name", VAULT_DRIVE_FOLDER_NAME),
                    formatVersion = 1
                )
            )
        }
        discovered
    }

    internal fun listDriveChildren(accessToken: String, parentFolderId: String): List<JSONObject> =
        listDriveFilesWithParents("'$parentFolderId' in parents and trashed = false", accessToken)

    internal fun findDriveSubfolder(accessToken: String, parentFolderId: String, name: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        return listDriveFiles(
            "'$parentFolderId' in parents and trashed = false and " +
                "mimeType = 'application/vnd.google-apps.folder' and name = '$escaped'",
            accessToken
        ).firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }
    }

    internal fun findOrCreateDriveSubfolder(accessToken: String, parentFolderId: String, name: String): String =
        synchronized(folderLock) {
            findDriveSubfolder(accessToken, parentFolderId, name)
                ?: createSubfolderInParent(accessToken, parentFolderId, name)
        }

    internal fun findDriveChildFile(accessToken: String, parentFolderId: String, name: String): JSONObject? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        return listDriveFiles(
            "'$parentFolderId' in parents and trashed = false and " +
                "mimeType != 'application/vnd.google-apps.folder' and name = '$escaped'",
            accessToken
        ).firstOrNull()
    }

    internal fun findDriveChildAny(accessToken: String, parentFolderId: String, name: String): JSONObject? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        return listDriveFiles(
            "'$parentFolderId' in parents and trashed = false and name = '$escaped'",
            accessToken
        ).firstOrNull()
    }

    internal fun downloadDriveBytesById(accessToken: String, fileId: String): ByteArray? =
        downloadDriveFileBytes(fileId, accessToken)

    internal fun uploadDriveChildBytes(
        accessToken: String,
        parentFolderId: String,
        fileName: String,
        payload: ByteArray,
        existingDriveId: String? = null,
        appProperties: Map<String, String> = emptyMap()
    ): String {
        val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val resolvedDriveId = existingDriveId?.takeIf { it.isNotBlank() }
            ?: listDriveFiles(
                "'$parentFolderId' in parents and trashed = false and " +
                    "mimeType != 'application/vnd.google-apps.folder' and name = '$escapedName'",
                accessToken
            ).firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }
        val boundary = "===encrypz_c9r_boundary_${System.currentTimeMillis()}==="
        val metadataJson = JSONObject().apply {
            put("name", fileName)
            if (appProperties.isNotEmpty()) {
                val props = JSONObject()
                appProperties.forEach { (k, v) -> if (v.isNotBlank()) props.put(k, v) }
                if (props.length() > 0) put("appProperties", props)
            }
            if (resolvedDriveId.isNullOrBlank()) {
                put("parents", JSONArray().put(parentFolderId))
            }
            put("mimeType", "application/octet-stream")
        }

        val url = if (!resolvedDriveId.isNullOrBlank()) {
            "$DRIVE_UPLOAD_API/${URLEncoder.encode(resolvedDriveId, "UTF-8")}?uploadType=multipart&fields=id,name"
        } else {
            "$DRIVE_UPLOAD_API?uploadType=multipart&fields=id,name"
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (!resolvedDriveId.isNullOrBlank()) "PATCH" else "POST"
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
        multipartBody.write(payload)
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

    internal fun deleteDriveItemById(accessToken: String, itemId: String): Boolean {
        return try {
            val encodedId = URLEncoder.encode(itemId, "UTF-8")
            val conn = (URL("$DRIVE_FILES_API/$encodedId").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Authorization", "Bearer $accessToken")
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

    private fun createSubfolderInParent(accessToken: String, parentFolderId: String, name: String): String {
        val createMeta = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray().put(parentFolderId))
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
            throw IOException("Failed to create folder '$name' in Google Drive ($code): $body")
        }
        return JSONObject(body).getString("id")
    }

    private fun listDriveFilesWithParents(query: String, accessToken: String): List<JSONObject> =
        com.encrypz.privacy.drive.DrivePages.collect { pageToken ->
            val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val url = "$DRIVE_FILES_API?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&spaces=drive&fields=nextPageToken,files(id,name,mimeType,parents,size,createdTime,appProperties)&pageSize=100$page"
            val json = executeJsonGet(url, accessToken)
            val array = json.optJSONArray("files") ?: JSONArray()
            com.encrypz.privacy.drive.DrivePages.Page((0 until array.length()).map { array.getJSONObject(it) },
                json.optString("nextPageToken").takeIf { it.isNotBlank() })
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
                    reason = "Google Drive permission not granted yet. It will be requested on your next login.",
                    retryable = false
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
            if (firstEx is kotlinx.coroutines.CancellationException) throw firstEx
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
                    if (retryEx is kotlinx.coroutines.CancellationException) throw retryEx
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
        // A response can be lost after Drive commits the upload. Reuse the queued encrypted
        // name on retry so that a network failure does not create a second cloud copy.
        val escapedName = encryptedFileName.replace("\\", "\\\\").replace("'", "\\'")
        val resolvedDriveId = existingDriveId?.takeIf { it.isNotBlank() }
            ?: listDriveFiles("'$folderId' in parents and trashed = false and name = '$escapedName'",
                accessToken).firstOrNull()?.getString("id")
        val boundary = "===encrypz_vault_boundary_${System.currentTimeMillis()}==="
        val metadataJson = JSONObject().apply {
            put("name", encryptedFileName)
            if (encryptedCreationDate.isNotBlank()) put("appProperties", JSONObject().put("encCreated", encryptedCreationDate))
            if (resolvedDriveId.isNullOrBlank()) {
                put("parents", JSONArray().put(folderId))
            }
            put("mimeType", "application/octet-stream")
            put(
                "description",
                "Encrypz AES-256-GCM Encrypted File inside '$VAULT_DRIVE_FOLDER_NAME' folder (IV: 0x$ivHex)"
            )
        }

        val url = if (!resolvedDriveId.isNullOrBlank()) {
            val encodedId = URLEncoder.encode(resolvedDriveId, "UTF-8")
            "$DRIVE_UPLOAD_API/$encodedId?uploadType=multipart&fields=id,name"
        } else {
            "$DRIVE_UPLOAD_API?uploadType=multipart&fields=id,name"
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (!resolvedDriveId.isNullOrBlank()) "PATCH" else "POST"
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
