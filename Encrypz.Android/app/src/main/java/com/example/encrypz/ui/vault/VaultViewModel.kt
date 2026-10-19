package com.example.encrypz.ui.vault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.encrypz.auth.GoogleAuthConfig
import com.example.encrypz.crypto.VaultFileNames
import com.example.encrypz.crypto.VaultCrypto
import com.example.encrypz.data.LocalVaultStorage
import com.example.encrypz.data.AccountApiClient
import com.example.encrypz.data.MongoAtlasClient.MongoVaultUserRecord
import com.example.encrypz.drive.GoogleDriveSyncManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

enum class VaultCategoryFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    DOCUMENTS("Documents")
}

enum class VaultViewMode {
    GRID,
    LIST
}

enum class AutoLockTimeout(val label: String) {
    IMMEDIATE("Immediately on background"),
    ONE_MINUTE("After 1 minute"),
    FIVE_MINUTES("After 5 minutes"),
    FIFTEEN_MINUTES("After 15 minutes")
}

enum class DriveSyncState(val badgeLabel: String) {
    SYNCING_TO_DRIVE("Uploading .enc to Drive..."),
    SYNCED_TO_DRIVE("Saved in Google Drive (.enc)"),
    LOCAL_ENCRYPTED("AES-256-GCM Encrypted")
}

data class BreadcrumbItem(
    val id: String?,
    val name: String
)

data class VaultFolder(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val createdAt: String,
    val ivHex: String,
    val isDeleted: Boolean = false
)

data class VaultFileEntry(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val sizeBytes: Long,
    val uploadedAt: String,
    val createdAtMillis: Long? = null,
    val googleAccountEmail: String,
    val encryptedPayload: ByteArray,
    val encryptedThumbnail: ByteArray? = null,
    val ivHex: String,
    val isDeleted: Boolean = false,
    val driveFileId: String? = null,
    val driveSyncState: DriveSyncState = DriveSyncState.LOCAL_ENCRYPTED
) {
    val cleanFileName: String
        get() = name.removeSuffix(".enc")

    val extension: String
        get() = cleanFileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val category: VaultCategoryFilter
        get() {
            return when (extension) {
                "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic" -> VaultCategoryFilter.PHOTOS
                "mp4", "webm", "mov", "avi", "mkv", "3gp" -> VaultCategoryFilter.VIDEOS
                "pdf", "doc", "docx", "txt", "md", "json", "xls", "xlsx", "csv", "key", "xml", "log" -> VaultCategoryFilter.DOCUMENTS
                else -> VaultCategoryFilter.DOCUMENTS
            }
        }
}

enum class UnlockAttemptResult {
    SERVICE_ERROR,
    SUCCESS,
    WRONG_PASSWORD,
    WRONG_PIN,
    ACCOUNT_NOT_CREATED,
    PIN_NOT_CONFIGURED
}

data class VaultUiState(
    val isGoogleConnected: Boolean = false,
    val connectedGoogleEmail: String = "",
    val isDrivePermissionGranted: Boolean = false,
    val driveSetupError: String? = null,
    val pendingDriveAuthorization: PendingIntent? = null,
    val driveAuthorizationEmail: String? = null,
    val isRequestingDrivePermission: Boolean = false,
    val oauthClientId: String = GoogleAuthConfig.CLIENT_ID,
    val oauthClientSecretMasked: String = GoogleAuthConfig.maskedClientSecret,
    val isVaultUnlocked: Boolean = false,
    val hasExistingVaultAccount: Boolean = false,
    val isCheckingAccountStatus: Boolean = false,
    val hasCompletedFirstLogin: Boolean = false,
    val isPinSet: Boolean = false,
    val promptPinSetupAfterFirstLogin: Boolean = false,
    val screenshotProtectionEnabled: Boolean = true,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.IMMEDIATE,
    val currentFolderId: String? = null,
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem(null, "My Vault")),
    val searchQuery: String = "",
    val selectedCategory: VaultCategoryFilter = VaultCategoryFilter.ALL,
    val viewMode: VaultViewMode = VaultViewMode.GRID,
    val folders: List<VaultFolder> = emptyList(),
    val files: List<VaultFileEntry> = emptyList(),
    val statusBannerMessage: String? = null,
    val isSyncingWithDrive: Boolean = false,
    val isImportingFiles: Boolean = false,
    val pendingDriveConsentIntent: Intent? = null,
    val pendingDriveConsentFileId: String? = null,
    val pendingDriveSaverFileId: String? = null,
    val pendingDriveSaverFileName: String? = null
) {
    val visibleFolders: List<VaultFolder>
        get() = folders.filter { folder ->
            !folder.isDeleted &&
                folder.parentFolderId == currentFolderId &&
                selectedCategory == VaultCategoryFilter.ALL &&
                (searchQuery.isBlank() || folder.name.contains(searchQuery, ignoreCase = true))
        }

    val visibleFiles: List<VaultFileEntry>
        get() = files.filter { file ->
            !file.isDeleted &&
                (if (searchQuery.isNotBlank() || selectedCategory != VaultCategoryFilter.ALL) true else file.folderId == currentFolderId) &&
                (selectedCategory == VaultCategoryFilter.ALL || file.category == selectedCategory) &&
                file.matchesVaultSearch(searchQuery)
        }.sortedByDescending { it.sortDate }

    val deletedFolders: List<VaultFolder>
        get() = folders.filter { it.isDeleted }

    val deletedFiles: List<VaultFileEntry>
        get() = files.filter { it.isDeleted }

    val activeFileCount: Int
        get() = files.count { !it.isDeleted }

    val activeFolderCount: Int
        get() = folders.count { !it.isDeleted }

    val totalEncryptedBytes: Long
        get() = files.filter { !it.isDeleted }.sumOf { it.sizeBytes }

    val syncedToDriveCount: Int
        get() = files.count { !it.isDeleted && it.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE }
}

class VaultViewModel : ViewModel() {
    private val previewCacheDelegate = lazy { VaultPreviewCache() }
    internal val previewCache: VaultPreviewCache get() = previewCacheDelegate.value
    private fun clearPreviewCache() {
        if (previewCacheDelegate.isInitialized()) previewCache.clear()
    }
    private var activeVaultKey: SecretKey = VaultCrypto.generateVaultKey()
    private var salt: ByteArray = VaultCrypto.generateSalt()
    private var wrappedVaultKey: ByteArray = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)
    private var isStorageLoaded: Boolean = false
    private val userAuthRecords = ConcurrentHashMap<String, MongoVaultUserRecord>()

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /**
     * Initializes persistent local storage for the AES-256-GCM vault key and encrypted .enc blobs
     * so uploaded files survive app restarts and can be decrypted across sessions.
     */
    fun ensureLocalStorageLoaded(context: Context) {
        if (isStorageLoaded) return

        val keyBundle = LocalVaultStorage.loadOrCreateKeyBundle(context)
        activeVaultKey = keyBundle.vaultKey
        salt = keyBundle.salt
        wrappedVaultKey = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)

        isStorageLoaded = true
        val persisted = LocalVaultStorage.loadVaultData(context)
        if (persisted != null) {
            _uiState.update { state ->
                state.copy(
                    folders = persisted.folders,
                    files = persisted.files.map { file ->
                        if (state.connectedGoogleEmail.isNotBlank() && file.googleAccountEmail.isBlank()) {
                            file.copy(googleAccountEmail = state.connectedGoogleEmail)
                        } else {
                            file
                        }
                    }
                )
            }
        } else {
            val freshInitialState = createInitialState()
            val currentEmail = _uiState.value.connectedGoogleEmail
            val initialFiles = freshInitialState.files.map { file ->
                if (currentEmail.isNotBlank()) file.copy(googleAccountEmail = currentEmail) else file
            }
            _uiState.update { state ->
                state.copy(
                    folders = freshInitialState.folders,
                    files = initialFiles
                )
            }
            LocalVaultStorage.saveVaultData(context, freshInitialState.folders, initialFiles)
        }
    }

    private fun persistIfPossible(context: Context?) {
        if (context != null) {
            val current = _uiState.value
            LocalVaultStorage.saveVaultData(context, current.folders, current.files)
        }
    }

    fun connectGoogleAccount(
        email: String,
        context: Context? = null,
        requestDrivePermissionIfNeeded: Boolean = false
    ) {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) return
        if (!_uiState.value.connectedGoogleEmail.equals(cleanEmail, ignoreCase = true)) {
            clearPreviewCache()
            _uiState.update { it.copy(isRequestingDrivePermission = false,
                pendingDriveAuthorization = null, driveAuthorizationEmail = null, driveSetupError = null) }
        }
        val emailKey = cleanEmail.lowercase(Locale.ROOT)
        var localRecord: MongoVaultUserRecord? = userAuthRecords[emailKey]
        if (context != null) {
            if (LocalVaultStorage.selectAccount(context, emailKey)) {
                isStorageLoaded = false
                _uiState.update { it.copy(isVaultUnlocked = false, files = emptyList(), folders = emptyList()) }
            }
            ensureLocalStorageLoaded(context)
            LocalVaultStorage.loadUserAuthRecord(context, emailKey)?.let { loaded ->
                localRecord = loaded
                userAuthRecords[emailKey] = loaded
            }
        }

        _uiState.update { state ->
            state.copy(
                isGoogleConnected = true,
                connectedGoogleEmail = cleanEmail,
                isDrivePermissionGranted = state.isDrivePermissionGranted &&
                    state.connectedGoogleEmail.equals(cleanEmail, ignoreCase = true),
                hasExistingVaultAccount = localRecord != null,
                isCheckingAccountStatus = localRecord == null && context != null,
                hasCompletedFirstLogin = localRecord?.hasLoggedInOnce == true,
                isPinSet = !localRecord?.pinVerifierHex.isNullOrBlank(),

                statusBannerMessage = "Connected to Google Drive ($cleanEmail)"
            )
        }
        persistIfPossible(context)

        // Drive setup must not wait for the account metadata server.
        if (context != null && requestDrivePermissionIfNeeded) {
            requestGoogleDrivePermission(context, cleanEmail)
        }

        if (context != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching { AccountApiClient.exists(context, emailKey) }
                _uiState.update { state ->
                    if (!state.connectedGoogleEmail.equals(cleanEmail, true)) state else state.copy(
                        hasExistingVaultAccount = result.getOrNull() ?: true,
                        isCheckingAccountStatus = false,
                        statusBannerMessage = result.exceptionOrNull()?.message ?: state.statusBannerMessage
                    )
                }
            }
        }
    }

    /**
     * Requests Google Drive permission during Login or Account Creation so the app can create
     * the `Encrypz` folder in Google Drive and directly save uploaded `.enc` files into it.
     */
    fun requestGoogleDrivePermission(
        context: Context,
        emailOverride: String? = null,
        onCompleted: ((Boolean) -> Unit)? = null,
        authorizationData: Intent? = null
    ) {
        val targetEmail = (emailOverride ?: _uiState.value.connectedGoogleEmail)
            .trim()
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }
        val emailKey = targetEmail.lowercase(Locale.ROOT)
        val existingRecord = if (emailKey.isNotBlank()) {
            userAuthRecords[emailKey] ?: LocalVaultStorage.loadUserAuthRecord(context, emailKey)
        } else {
            null
        }

        // A permission request is already running (e.g. account selection + unlock both triggered it)
        if (_uiState.value.isRequestingDrivePermission) {
            return
        }

        _uiState.update {
            it.copy(
                isRequestingDrivePermission = true,
                driveSetupError = null,
                statusBannerMessage = "Requesting Google Drive permission for 'Encrypz' folder..."
            )
        }

        viewModelScope.launch {
            val result = GoogleDriveSyncManager.requestDrivePermissionAndEnsureFolder(
                context = context,
                accountEmail = targetEmail,
                fallbackRefreshTokenFromDb = existingRecord?.googleRefreshToken,
                authorizationData = authorizationData
            )

            if (!_uiState.value.connectedGoogleEmail.equals(targetEmail, ignoreCase = true)) return@launch
            if (result.authorizationIntent != null && authorizationData == null) {
                _uiState.update {
                    it.copy(pendingDriveAuthorization = result.authorizationIntent,
                        driveAuthorizationEmail = targetEmail)
                }
                return@launch
            }

            if (result.consentIntent != null) {
                _uiState.update {
                    it.copy(isRequestingDrivePermission = false,
                        pendingDriveConsentIntent = result.consentIntent)
                }
                return@launch
            }

            val resolvedEmail = result.email.trim().ifBlank { targetEmail }
            val resolvedKey = resolvedEmail.lowercase(Locale.ROOT)

            if (result.granted && resolvedKey.isNotBlank()) {
                val currentRec = userAuthRecords[resolvedKey]
                    ?: LocalVaultStorage.loadUserAuthRecord(context, resolvedKey)
                if (currentRec != null) {
                    val updatedRec = currentRec.copy(
                        driveFolderId = result.folderId ?: currentRec.driveFolderId,
                        googleRefreshToken = result.refreshToken ?: currentRec.googleRefreshToken,
                        updatedAt = currentFormattedDate()
                    )
                    userAuthRecords[resolvedKey] = updatedRec
                    LocalVaultStorage.saveUserAuthRecord(context, updatedRec)
                }
            }

            _uiState.update { state ->
                state.copy(
                    isGoogleConnected = state.isGoogleConnected || resolvedEmail.isNotBlank(),
                    connectedGoogleEmail = resolvedEmail.ifBlank { state.connectedGoogleEmail },
                    isDrivePermissionGranted = result.granted,
                    isRequestingDrivePermission = false,
                    driveSetupError = if (result.granted) null else result.error ?: "Google Drive permission was not granted.",
                    statusBannerMessage = if (result.granted) {
                        "Google Drive '${GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME}' folder permission granted"
                    } else {
                        result.error ?: "Google Drive permission was not granted."
                    }
                )
            }

            if (result.granted && _uiState.value.isVaultUnlocked) {
                // Directly upload any unsynced local encrypted files to the Encrypz folder on Google Drive
                val unsynced = _uiState.value.files.filter {
                    !it.isDeleted && (it.driveSyncState != DriveSyncState.SYNCED_TO_DRIVE || it.driveFileId.isNullOrBlank())
                }
                for (file in unsynced) {
                    saveFileToGoogleDrive(context, file.id, forceDocumentSaver = false)
                }
            }

            onCompleted?.invoke(result.granted)
        }
    }

    fun consumeDriveAuthorization() {
        _uiState.update { it.copy(pendingDriveAuthorization = null) }
    }

    fun onDriveAuthorizationResult(context: Context, data: Intent?, granted: Boolean) {
        val email = _uiState.value.driveAuthorizationEmail ?: return
        _uiState.update { it.copy(isRequestingDrivePermission = false,
            pendingDriveAuthorization = null, driveAuthorizationEmail = null) }
        if (!_uiState.value.connectedGoogleEmail.equals(email, ignoreCase = true)) return
        // Google can return a structured API error even when the activity was cancelled.
        if (data != null) {
            requestGoogleDrivePermission(context, email, authorizationData = data)
        } else {
            val message = if (granted) "Google returned no authorization result. Try Allow Google Drive access again."
                else "Google Drive setup was cancelled or closed. Tap Allow Google Drive access in Security to try again."
            _uiState.update { it.copy(isDrivePermissionGranted = false,
                driveSetupError = message, statusBannerMessage = message) }
        }
    }

    fun onDriveAuthorizationLaunchFailed(message: String) {
        _uiState.update { it.copy(isRequestingDrivePermission = false,
            pendingDriveAuthorization = null, driveAuthorizationEmail = null,
            isDrivePermissionGranted = false, driveSetupError = message, statusBannerMessage = message) }
    }

    fun disconnectGoogleAccount() {
        clearPreviewCache()
        _uiState.update {
            it.copy(
                isGoogleConnected = false,
                connectedGoogleEmail = "",
                pendingDriveAuthorization = null,
                driveAuthorizationEmail = null,
                driveSetupError = null,
                isDrivePermissionGranted = false,
                isRequestingDrivePermission = false,
                isVaultUnlocked = false,
                hasExistingVaultAccount = false,
                isCheckingAccountStatus = false,
                hasCompletedFirstLogin = false,
                isPinSet = false,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = null
            )
        }
    }

    /**
     * Evaluates a Master Password login or account creation attempt:
     * - If `isCreatingNewVault == false` and the email has no Encrypz account in MongoDB Atlas or
     *   LocalVaultStorage, returns `UnlockAttemptResult.ACCOUNT_NOT_CREATED` so the UI directs the
     *   user to Account Creation.
     * - If the user enters the wrong master password, returns `UnlockAttemptResult.WRONG_PASSWORD`.
     * - Upon first successful login, sets `hasCompletedFirstLogin = true` and prompts the user to
     *   configure their Quick-Unlock PIN (`promptPinSetupAfterFirstLogin = true`).
     */
    fun attemptUnlockWithMasterPassword(
        password: String,
        isCreatingNewVault: Boolean = false,
        context: Context? = null
    ): UnlockAttemptResult {
        if (password.isBlank()) return UnlockAttemptResult.WRONG_PASSWORD
        if (context != null) {
            ensureLocalStorageLoaded(context)
        }

        val rawEmail = _uiState.value.connectedGoogleEmail.trim()
        val emailKey = rawEmail.lowercase(Locale.ROOT).ifBlank { "default_vault_user" }

        val existingRecord = userAuthRecords[emailKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, emailKey) }
        val activeRecord: MongoVaultUserRecord
        try {
            if (context != null) {
                activeRecord = AccountApiClient.authenticate(context, emailKey, password,
                    isCreatingNewVault, activeVaultKey, existingRecord)
            } else {
                // In-memory preview/test accounts never contact a real database.
                if (!isCreatingNewVault && existingRecord == null) return UnlockAttemptResult.ACCOUNT_NOT_CREATED
                if (isCreatingNewVault && existingRecord != null) return UnlockAttemptResult.SERVICE_ERROR
                val recordSalt = existingRecord?.let { VaultCrypto.fromHex(it.passwordSaltHex) } ?: salt
                val wrapping = VaultCrypto.deriveWrappingKey(password, recordSalt)
                activeRecord = existingRecord ?: MongoVaultUserRecord(emailKey, VaultCrypto.toHex(recordSalt),
                    VaultCrypto.accountProof(wrapping), VaultCrypto.toHex(VaultCrypto.encryptVaultKey(activeVaultKey, wrapping)),
                    credentialVersion = 2)
            }
            val recordSalt = VaultCrypto.fromHex(activeRecord.passwordSaltHex)
            val wrapping = if (activeRecord.credentialVersion == 2) VaultCrypto.deriveWrappingKey(password, recordSalt)
                else VaultCrypto.deriveSessionKeyFast(password, recordSalt)
            // Restore the saved key. Never replace it with this device's random key.
            val restoredKey = VaultCrypto.decryptVaultKey(VaultCrypto.fromHex(activeRecord.wrappedVaultKeyHex), wrapping)
            check(_uiState.value.connectedGoogleEmail.trim().equals(rawEmail, ignoreCase = true)) {
                "Google account changed during login. Please retry."
            }
            if (context != null) {
                LocalVaultStorage.saveKeyBundle(context, restoredKey, recordSalt)
                LocalVaultStorage.saveUserAuthRecord(context, activeRecord)
            }
            activeVaultKey = restoredKey
            salt = recordSalt
            wrappedVaultKey = VaultCrypto.fromHex(activeRecord.wrappedVaultKeyHex)
            userAuthRecords[emailKey] = activeRecord
        } catch (e: javax.crypto.AEADBadTagException) {
            _uiState.update { it.copy(isVaultUnlocked = false, statusBannerMessage = "Wrong master password. Please enter the correct master password.") }
            return UnlockAttemptResult.WRONG_PASSWORD
        } catch (e: Exception) {
            _uiState.update { it.copy(isVaultUnlocked = false, statusBannerMessage =
                if (e is AccountApiClient.AccountError || e is IllegalStateException) e.message
                else "Could not verify your account. Check your connection and retry.") }
            return UnlockAttemptResult.SERVICE_ERROR
        }

        val pinAlreadyConfigured = !activeRecord.pinVerifierHex.isNullOrBlank()
        val hasDriveAccess = _uiState.value.isDrivePermissionGranted

        _uiState.update {
            it.copy(
                isGoogleConnected = true,
                isDrivePermissionGranted = hasDriveAccess,
                isVaultUnlocked = true,
                hasExistingVaultAccount = true,
                isCheckingAccountStatus = false,
                hasCompletedFirstLogin = true,
                isPinSet = pinAlreadyConfigured,
                promptPinSetupAfterFirstLogin = !pinAlreadyConfigured,
                statusBannerMessage = "Vault unlocked with AES-256-GCM"
            )
        }

        recoverFileDates(context)

        // Request Google Drive permission if not yet granted, and ensure the "Encrypz" folder exists in Google Drive
        if (context != null) {
            val driveEmail = _uiState.value.connectedGoogleEmail
                .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }
            if (driveEmail.isNotBlank()) {
                requestGoogleDrivePermission(context, driveEmail)
            }
        }

        return UnlockAttemptResult.SUCCESS
    }

    fun unlockWithMasterPassword(
        password: String,
        isCreatingNewVault: Boolean = false,
        context: Context? = null
    ): Boolean {
        return attemptUnlockWithMasterPassword(password, isCreatingNewVault, context) == UnlockAttemptResult.SUCCESS
    }

    /**
     * Configures or updates the 6 digit Quick-Unlock PIN after the user has completed their first login.
     */
    fun setupUnlockPin(pin: String, context: Context? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it in '0'..'9' }) {
            return false
        }
        val emailKey = _uiState.value.connectedGoogleEmail
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "default_vault_user" }

        val existingRecord = userAuthRecords[emailKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, emailKey) }
            ?: return false
        val recordSalt = VaultCrypto.fromHex(existingRecord.passwordSaltHex)
        val pinVerifier = VaultCrypto.computePinVerifierHex(cleanPin, recordSalt)

        val updatedRecord = existingRecord.copy(
            pinVerifierHex = pinVerifier,
            hasLoggedInOnce = true,
            updatedAt = currentFormattedDate()
        )

        userAuthRecords[emailKey] = updatedRecord
        if (context != null) {
            LocalVaultStorage.saveUserAuthRecord(context, updatedRecord)
        }

        _uiState.update {
            it.copy(
                hasCompletedFirstLogin = true,
                isPinSet = true,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Quick-Unlock PIN saved â€” you can now unlock your vault with your PIN"
            )
        }
        return true
    }

    /**
     * Unlocks the vault using the user's Quick-Unlock PIN (available after first Master Password login).
     */
    fun unlockWithPin(pin: String, context: Context? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it in '0'..'9' }) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = "Wrong PIN. Please enter the correct PIN."
                )
            }
            return false
        }
        if (context != null) {
            ensureLocalStorageLoaded(context)
        }

        val emailKey = _uiState.value.connectedGoogleEmail
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "default_vault_user" }

        var existingRecord: MongoVaultUserRecord? = userAuthRecords[emailKey]
        if (existingRecord == null && context != null) {
            existingRecord = LocalVaultStorage.loadUserAuthRecord(context, emailKey)
            if (existingRecord != null) {
                userAuthRecords[emailKey] = existingRecord
            }
        }

        val expectedPinVerifier = existingRecord?.pinVerifierHex
        if (existingRecord == null || !existingRecord.hasLoggedInOnce || expectedPinVerifier.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = "Please sign in with your master password first to enable PIN unlock."
                )
            }
            return false
        }

        val recordSalt = VaultCrypto.fromHex(existingRecord.passwordSaltHex).takeIf { it.isNotEmpty() } ?: salt
        val candidatePinVerifier = VaultCrypto.computePinVerifierHex(cleanPin, recordSalt)
        if (!candidatePinVerifier.equals(expectedPinVerifier, ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = "Wrong PIN. Please enter the correct PIN."
                )
            }
            return false
        }

        val driveEmailForPin = _uiState.value.connectedGoogleEmail
            .ifBlank { context?.let { GoogleAuthConfig.getSavedGoogleEmail(it) }.orEmpty() }
        val hasDriveAccess = _uiState.value.isDrivePermissionGranted

        _uiState.update {
            it.copy(
                isGoogleConnected = true,
                isDrivePermissionGranted = hasDriveAccess,
                isVaultUnlocked = true,
                hasExistingVaultAccount = true,
                hasCompletedFirstLogin = true,
                isPinSet = true,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Vault unlocked with PIN (AES-256-GCM)"
            )
        }

        recoverFileDates(context)
        if (context != null && driveEmailForPin.isNotBlank()) {
            requestGoogleDrivePermission(context, driveEmailForPin)
        }
        return true
    }

    fun fingerprintSetupKey(email: String): ByteArray? {
        val state = _uiState.value
        if (!state.isVaultUnlocked || !state.hasCompletedFirstLogin ||
            !state.connectedGoogleEmail.equals(email, ignoreCase = true)) return null
        return activeVaultKey.encoded.copyOf()
    }

    fun unlockWithFingerprintKey(email: String, key: ByteArray, context: Context): Boolean {
        if (email.isBlank() || !_uiState.value.connectedGoogleEmail.equals(email, ignoreCase = true)) return false
        ensureLocalStorageLoaded(context)
        val record = LocalVaultStorage.loadUserAuthRecord(context, email.trim().lowercase(Locale.ROOT))
            ?: return false
        if (!record.hasLoggedInOnce || key.size != 32 ||
            !java.security.MessageDigest.isEqual(key, activeVaultKey.encoded)) return false
        activeVaultKey = javax.crypto.spec.SecretKeySpec(key.copyOf(), "AES")
        _uiState.update { it.copy(isVaultUnlocked = true, hasExistingVaultAccount = true,
            hasCompletedFirstLogin = true, promptPinSetupAfterFirstLogin = false,
            statusBannerMessage = "Vault unlocked with fingerprint") }
        recoverFileDates(context)
        return true
    }

    fun removeUnlockPin(context: Context? = null) {
        val emailKey = _uiState.value.connectedGoogleEmail
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "default_vault_user" }
        val existingRecord = userAuthRecords[emailKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, emailKey) }
        if (existingRecord != null) {
            val updated = existingRecord.copy(pinVerifierHex = null, updatedAt = currentFormattedDate())
            userAuthRecords[emailKey] = updated
            if (context != null) {
                LocalVaultStorage.saveUserAuthRecord(context, updated)
            }
        }
        _uiState.update {
            it.copy(
                isPinSet = false,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Quick-Unlock PIN removed â€” Master Password required on next unlock"
            )
        }
    }

    fun dismissPinSetupPrompt() {
        _uiState.update {
            it.copy(promptPinSetupAfterFirstLogin = false)
        }
    }

    fun onAppBackgrounded() {
        if (_uiState.value.isVaultUnlocked) lockVault()
    }

    fun lockVault() {
        clearPreviewCache()
        _uiState.update {
            it.copy(
                isVaultUnlocked = false,
                promptPinSetupAfterFirstLogin = false,
                currentFolderId = null,
                breadcrumbs = listOf(BreadcrumbItem(null, "My Vault")),
                searchQuery = "",
                selectedCategory = VaultCategoryFilter.ALL,
                statusBannerMessage = "Vault locked â€” keys cleared from active session"
            )
        }
    }

    private fun recoverFileDates(context: Context?) {
        _uiState.update { state -> state.copy(files = state.files.map { file ->
            if (file.createdAtMillis != null) file else file.copy(createdAtMillis =
                decryptFileBytes(file)?.let { embeddedCreationDate(it, context, file.name) } ?: file.sortDate)
        }) }
        persistIfPossible(context)
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectCategory(category: VaultCategoryFilter) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun toggleViewMode() {
        _uiState.update {
            val next = if (it.viewMode == VaultViewMode.LIST) VaultViewMode.GRID else VaultViewMode.LIST
            it.copy(viewMode = next)
        }
    }

    fun setViewMode(mode: VaultViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun navigateToFolder(folder: VaultFolder) {
        _uiState.update { state ->
            val updatedCrumbs = state.breadcrumbs + BreadcrumbItem(folder.id, folder.name)
            state.copy(
                currentFolderId = folder.id,
                breadcrumbs = updatedCrumbs,
                searchQuery = "",
                selectedCategory = VaultCategoryFilter.ALL
            )
        }
    }

    fun navigateToBreadcrumb(index: Int) {
        _uiState.update { state ->
            val targetCrumbs = state.breadcrumbs.take(index + 1)
            val targetFolderId = targetCrumbs.lastOrNull()?.id
            state.copy(
                currentFolderId = targetFolderId,
                breadcrumbs = targetCrumbs
            )
        }
    }

    fun navigateUp(): Boolean {
        val currentCrumbs = _uiState.value.breadcrumbs
        if (currentCrumbs.size <= 1) return false
        navigateToBreadcrumb(currentCrumbs.size - 2)
        return true
    }

    fun createFolder(folderName: String, context: Context? = null) {
        val trimmed = folderName.trim()
        if (trimmed.isEmpty()) return
        val encryptedNameBytes = VaultCrypto.encryptPayload(trimmed.encodeToByteArray(), activeVaultKey)
        val newFolder = VaultFolder(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            parentFolderId = _uiState.value.currentFolderId,
            createdAt = currentFormattedDate(),
            ivHex = VaultCrypto.extractIvHex(encryptedNameBytes)
        )
        _uiState.update { state ->
            state.copy(
                folders = listOf(newFolder) + state.folders,
                statusBannerMessage = "Encrypted folder \"$trimmed\" created"
            )
        }
        persistIfPossible(context)
    }

    /**
     * Encrypts the file client-side with AES-256-GCM (or imports an already-encrypted .enc file),
     * saves the encrypted .enc blob to local storage, and directly uploads the encrypted .enc file
     * into the `Encrypz` folder on the user's Google Drive.
     */
    fun importSelectedFiles(context: Context, uris: List<Uri>) {
        val selected = uris.distinct()
        if (selected.isEmpty()) return
        if (!validImportCount(selected.size) || _uiState.value.isImportingFiles) {
            com.example.encrypz.AppNotifications.record(context,
                "Select up to $MAX_IMPORT_FILES files and wait for the current batch to finish.", true)
            return
        }
        ensureLocalStorageLoaded(context)
        val owner = _uiState.value.connectedGoogleEmail
        _uiState.update { it.copy(isImportingFiles = true) }
        viewModelScope.launch {
            try {
                for (uri in selected) {
                    val unlocked = _uiState.first { it.isVaultUnlocked || it.connectedGoogleEmail != owner }
                    if (unlocked.connectedGoogleEmail != owner) break
                    try {
                        val (picked, sourceDate) = withContext(Dispatchers.IO) {
                            readPickedUriForVault(context, uri) to sourceFileDate(context, uri)
                        }
                        val ready = _uiState.first { it.isVaultUnlocked || it.connectedGoogleEmail != owner }
                        if (ready.connectedGoogleEmail != owner) break
                        encryptAndAddFile(picked.fileName, picked.rawBytes, picked.thumbnailBytes,
                            picked.originalSizeBytes, context, sourceCreatedAtMillis = sourceDate)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        com.example.encrypz.AppNotifications.showTransfer(context,
                            "Upload skipped: ${error.message ?: "Unable to read the selected file."}", true)
                    }
                }
            } finally {
                _uiState.update { it.copy(isImportingFiles = false) }
            }
        }
    }

    fun encryptAndAddFile(
        fileName: String,
        rawBytes: ByteArray,
        thumbnailBytes: ByteArray? = null,
        originalSizeBytes: Long = rawBytes.size.toLong(),
        context: Context? = null,
        autoSaveToGoogleDrive: Boolean = true,
        sourceCreatedAtMillis: Long? = null
    ) {
        if (context != null) {
            ensureLocalStorageLoaded(context)
        }

        val restoredName = try {
            VaultFileNames.decryptOrLegacy(fileName, activeVaultKey)
        } catch (_: Exception) {
            _uiState.update { it.copy(statusBannerMessage = "This filename cannot be decrypted with the current vault key.") }
            return
        }
        val safeName = if (restoredName.endsWith(".enc")) restoredName else "$restoredName.enc"

        // Preserve valid Encrypz ciphertext; the source may be local or remote.
        val alreadyDecryptedPlaintext: ByteArray? = if (fileName.endsWith(".enc") && rawBytes.size > 28) {
            try {
                VaultCrypto.decryptPayload(rawBytes, activeVaultKey)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val finalEncryptedPayload: ByteArray
        val finalEncryptedThumb: ByteArray?
        val finalSizeBytes: Long

        if (alreadyDecryptedPlaintext != null) {
            // File was already encrypted with AES-256-GCM; keep its ciphertext and extract preview
            finalEncryptedPayload = rawBytes
            val extractedThumb = thumbnailBytes ?: if (context != null) {
                extractThumbnailFromDecryptedBytes(context, safeName, alreadyDecryptedPlaintext)
            } else {
                null
            }
            finalEncryptedThumb = extractedThumb?.let { VaultCrypto.encryptPayload(it, activeVaultKey) }
            finalSizeBytes = alreadyDecryptedPlaintext.size.toLong().coerceAtLeast(128L)
        } else {
            // Encrypt the raw file client-side using AES-256-GCM (96-bit IV + Ciphertext + 128-bit GCM Tag)
            finalEncryptedPayload = VaultCrypto.encryptPayload(rawBytes, activeVaultKey)
            finalEncryptedThumb = thumbnailBytes?.let { VaultCrypto.encryptPayload(it, activeVaultKey) }
            finalSizeBytes = originalSizeBytes.coerceAtLeast(rawBytes.size.toLong()).coerceAtLeast(128L)
        }

        val newFile = VaultFileEntry(
            id = UUID.randomUUID().toString(),
            name = safeName,
            folderId = _uiState.value.currentFolderId,
            sizeBytes = finalSizeBytes,
            uploadedAt = currentFormattedDate(),
            createdAtMillis = embeddedCreationDate(alreadyDecryptedPlaintext ?: rawBytes, context, safeName)
                ?: sourceCreatedAtMillis?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            googleAccountEmail = _uiState.value.connectedGoogleEmail.ifBlank { "Google Drive Vault" },
            encryptedPayload = finalEncryptedPayload,
            encryptedThumbnail = finalEncryptedThumb,
            ivHex = VaultCrypto.extractIvHex(finalEncryptedPayload),
            driveSyncState = if (context != null && autoSaveToGoogleDrive) {
                DriveSyncState.SYNCING_TO_DRIVE

            } else {
                DriveSyncState.LOCAL_ENCRYPTED
            }
        )

        _uiState.update { state ->
            state.copy(
                files = listOf(newFile) + state.files,
                statusBannerMessage = if (context != null && autoSaveToGoogleDrive) {
                    "Encrypted file queued for automatic upload to Google Drive 'Encrypz'."
                } else {
                    "File encrypted and stored on this device."
                }
            )
        }
        persistIfPossible(context)

        // Directly upload the AES-256-GCM encrypted .enc file into the "Encrypz" folder on Google Drive
        if (context != null && autoSaveToGoogleDrive) {
            saveFileToGoogleDrive(
                context = context,
                fileId = newFile.id,
                forceDocumentSaver = false
            )
        }
    }

    fun encryptAndAddSecureNote(
        title: String,
        content: String,
        context: Context? = null,
        autoSaveToGoogleDrive: Boolean = true
    ) {
        val cleanTitle = title.trim().ifEmpty { "secure_note" }.replace(" ", "_")
        val fileName = if (cleanTitle.contains('.')) "$cleanTitle.enc" else "$cleanTitle.txt.enc"
        encryptAndAddFile(
            fileName = fileName,
            rawBytes = content.encodeToByteArray(),
            context = context,
            autoSaveToGoogleDrive = autoSaveToGoogleDrive
        )
    }

    /**
     * Directly uploads a specific AES-256-GCM encrypted .enc file from the vault into the `Encrypz`
     * folder on the user's Google Drive via the Google Drive API v3.
     */
    private val driveUploadMutex = Mutex()
    private val activeDriveUploads = ConcurrentHashMap.newKeySet<String>()

    fun saveFileToGoogleDrive(
        context: Context,
        fileId: String,
        forceDocumentSaver: Boolean = false
    ) {
        if (!_uiState.value.isVaultUnlocked) return
        val targetFile = _uiState.value.files.find { it.id == fileId } ?: return
        val encryptedDriveName = VaultFileNames.encrypt(targetFile.name, activeVaultKey)
        val encryptedCreationDate = VaultCrypto.toHex(VaultCrypto.encryptPayload(
            targetFile.sortDate.toString().encodeToByteArray(), activeVaultKey))
        if (!activeDriveUploads.add(fileId)) return
        val accountEmail = _uiState.value.connectedGoogleEmail
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }

        _uiState.update { state ->
            state.copy(
                files = state.files.map {
                    if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.SYNCING_TO_DRIVE) else it
                }
            )
        }

        viewModelScope.launch {
            try {
                val result = driveUploadMutex.withLock {
                    GoogleDriveSyncManager.uploadEncryptedFileToDrive(context, accountEmail, targetFile,
                        encryptedDriveName, encryptedCreationDate)
                }
                when (result) {
                    is GoogleDriveSyncManager.DriveUploadResult.UploadedToDriveApi -> {
                        _uiState.update { state ->
                            state.copy(
                                isDrivePermissionGranted = true,
                                files = state.files.map {
                                    if (it.id == fileId) {
                                        it.copy(
                                            driveFileId = result.driveFileId,
                                            driveSyncState = DriveSyncState.SYNCED_TO_DRIVE
                                        )
                                    } else {
                                        it
                                    }
                                },
                                statusBannerMessage = "Saved encrypted \"${targetFile.name}\" directly to Google Drive (${result.folderName})"
                            )
                        }
                        persistIfPossible(context)
                        com.example.encrypz.AppNotifications.showTransfer(context,
                            _uiState.value.statusBannerMessage.orEmpty(),
                            false)
                    }

                    is GoogleDriveSyncManager.DriveUploadResult.NeedsUserConsent -> {
                        // Never prompt during upload â€” keep encrypted locally; it syncs after the next login grant
                        _uiState.update { state ->
                            state.copy(
                                files = state.files.map {
                                    if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.LOCAL_ENCRYPTED) else it
                                },
                                statusBannerMessage = "\"${targetFile.name}\" encrypted locally â€” will be saved to Google Drive 'Encrypz' after Drive permission is granted at login"
                            )
                        }
                        persistIfPossible(context)
                        com.example.encrypz.AppNotifications.showTransfer(context,
                            _uiState.value.statusBannerMessage.orEmpty(),
                            true)
                    }

                    is GoogleDriveSyncManager.DriveUploadResult.UploadFailed -> {
                        _uiState.update { state ->
                            state.copy(
                                files = state.files.map {
                                    if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.LOCAL_ENCRYPTED) else it
                                },
                                statusBannerMessage = "Could not upload \"${result.encryptedFileName}\" to Google Drive 'Encrypz' folder: ${result.reason}"
                            )
                        }
                        persistIfPossible(context)
                        com.example.encrypz.AppNotifications.showTransfer(context,
                            _uiState.value.statusBannerMessage.orEmpty(),
                            true)
                    }
                }
            } finally {
                activeDriveUploads.remove(fileId)
            }
        }
    }

    /**
     * Handles the result of the Google OAuth Drive permission consent dialog.
     */
    fun onDriveConsentResult(context: Context, granted: Boolean) {
        val pendingFileId = _uiState.value.pendingDriveConsentFileId
        _uiState.update {
            it.copy(
                pendingDriveConsentIntent = null,
                pendingDriveConsentFileId = null
            )
        }
        if (pendingFileId != null) {
            if (granted) {
                saveFileToGoogleDrive(context, pendingFileId, forceDocumentSaver = false)
            }
        } else if (granted) {
            requestGoogleDrivePermission(context)
        }
    }

    /**
     * Writes the AES-256-GCM encrypted .enc bytes to the Uri chosen in Android's Google Drive
     * Document Saver (`Intent.ACTION_CREATE_DOCUMENT`).
     */
    fun onDriveDocumentSaverResult(context: Context, targetUri: Uri?) {
        val pendingFileId = _uiState.value.pendingDriveSaverFileId
        _uiState.update {
            it.copy(
                pendingDriveSaverFileId = null,
                pendingDriveSaverFileName = null
            )
        }
        if (pendingFileId == null) return
        val file = _uiState.value.files.find { it.id == pendingFileId } ?: return

        if (targetUri == null) {
            _uiState.update { state ->
                state.copy(
                    files = state.files.map {
                        if (it.id == pendingFileId) it.copy(driveSyncState = DriveSyncState.LOCAL_ENCRYPTED) else it
                    },
                    statusBannerMessage = "\"${file.name}\" encrypted locally. Tap file -> 'Save .enc to Google Drive' anytime."
                )
            }
            persistIfPossible(context)
            return
        }

        viewModelScope.launch {
            val written = GoogleDriveSyncManager.writeBytesToUri(
                context = context,
                targetUri = targetUri,
                bytes = file.encryptedPayload
            )
            _uiState.update { state ->
                state.copy(
                    files = state.files.map {
                        if (it.id == pendingFileId) {
                            it.copy(
                                driveFileId = if (written) targetUri.toString() else it.driveFileId,
                                driveSyncState = if (written) DriveSyncState.SYNCED_TO_DRIVE else DriveSyncState.LOCAL_ENCRYPTED
                            )
                        } else {
                            it
                        }
                    },
                    statusBannerMessage = if (written) {
                        "Saved encrypted \"${file.name}\" (${formatFileSize(file.encryptedPayload.size.toLong())}) to Google Drive"
                    } else {
                        "Failed to write \"${file.name}\" to selected Google Drive location"
                    }
                )
            }
            persistIfPossible(context)
        }
    }

    /**
     * Syncs encrypted (.enc) files from the user's "Encrypz Vault" folder on Google Drive
     * and uploads any local unsynced encrypted files.
     */
    fun syncVaultWithGoogleDrive(context: Context, notifyOnCompletion: Boolean = true) {
        if (!_uiState.value.isVaultUnlocked || _uiState.value.isSyncingWithDrive) return
        ensureLocalStorageLoaded(context)
        val syncKey = activeVaultKey
        val email = _uiState.value.connectedGoogleEmail
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }

        if (email.isBlank()) {
            _uiState.update {
                it.copy(statusBannerMessage = "Sign in with your Google account to sync with Google Drive")
            }
            return
        }

        _uiState.update {
            it.copy(
                isSyncingWithDrive = true,
                statusBannerMessage = "Syncing encrypted .enc files with Google Drive ($email)..."
            )
        }

        viewModelScope.launch {
            val existingIds = _uiState.value.files.mapNotNull { it.driveFileId }.toSet()
            val existingNames = _uiState.value.files.map { it.name }.toSet()

            when (val syncResult = GoogleDriveSyncManager.syncEncryptedFilesFromDrive(
                context = context,
                accountEmail = email,
                existingDriveIds = existingIds,
                existingFileNames = existingNames
            )) {
                is GoogleDriveSyncManager.DriveRemoteSyncResult.Success -> {
                    if (!_uiState.value.isVaultUnlocked || !_uiState.value.connectedGoogleEmail.equals(email, true) || activeVaultKey !== syncKey) {
                        _uiState.update { it.copy(isSyncingWithDrive = false) }
                        return@launch
                    }
                    var unreadable = syncResult.failedDownloads
                    val importedEntries = syncResult.downloadedFiles.mapNotNull { remote ->
                        val restoredName = try {
                            VaultFileNames.decryptOrLegacy(remote.name, syncKey)
                        } catch (_: Exception) {
                            unreadable++
                            return@mapNotNull null
                        }
                        val decrypted = try {
                            VaultCrypto.decryptPayload(remote.encryptedBytes, syncKey)
                        } catch (_: Exception) {
                            unreadable++
                            return@mapNotNull null
                        }
                        val thumbBytes = extractThumbnailFromDecryptedBytes(context, restoredName, decrypted)
                        val encThumb = thumbBytes?.let { VaultCrypto.encryptPayload(it, syncKey) }
                        VaultFileEntry(
                            id = UUID.randomUUID().toString(),
                            name = restoredName,
                            folderId = null,
                            sizeBytes = decrypted.size.toLong(),
                            uploadedAt = currentFormattedDate(),
                            createdAtMillis = runCatching { VaultCrypto.decryptPayload(
                                VaultCrypto.fromHex(remote.encryptedCreationDate), syncKey).decodeToString().toLong().takeIf { it > 0 }
                            }.getOrNull() ?: embeddedCreationDate(decrypted, context, restoredName)
                                ?: parseVaultDate(remote.driveCreatedTime, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
                            googleAccountEmail = email,
                            encryptedPayload = remote.encryptedBytes,
                            encryptedThumbnail = encThumb,
                            ivHex = VaultCrypto.extractIvHex(remote.encryptedBytes),
                            driveFileId = remote.driveFileId,
                            driveSyncState = DriveSyncState.SYNCED_TO_DRIVE
                        )
                    }

                    _uiState.update { state ->
                        val mergedFiles = importedEntries.filter { remote -> state.files.none { it.driveFileId == remote.driveFileId } } + state.files
                        state.copy(
                            isSyncingWithDrive = false,
                            files = mergedFiles,
                            statusBannerMessage = when {
                                unreadable > 0 -> "Downloaded ${importedEntries.size} file(s); $unreadable file(s) could not be restored. Retry the download. Older files may require the original vault key from your previous device."
                                importedEntries.isNotEmpty() -> "Downloaded ${importedEntries.size} encrypted file(s) from Google Drive"
                                else -> "Google Drive Vault is up to date (${GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME})"
                            }
                        )
                    }
                    persistIfPossible(context)
                    // Opening/unlocking the app syncs silently; explicit transfers still notify.
                    if (notifyOnCompletion && (importedEntries.isNotEmpty() || unreadable > 0)) {
                        com.example.encrypz.AppNotifications.showTransfer(context,
                            _uiState.value.statusBannerMessage.orEmpty(), unreadable > 0)
                    }
                }

                is GoogleDriveSyncManager.DriveRemoteSyncResult.NeedsUserConsent -> {
                    _uiState.update { state ->
                        state.copy(
                            isSyncingWithDrive = false,
                            pendingDriveConsentIntent = syncResult.consentIntent,
                            pendingDriveConsentFileId = null
                        )
                    }
                }

                is GoogleDriveSyncManager.DriveRemoteSyncResult.Unavailable -> {
                    _uiState.update { state ->
                        state.copy(
                            isSyncingWithDrive = false,
                            statusBannerMessage = syncResult.reason
                        )
                    }
                }
            }
        }
    }

    /**
     * Exports a decrypted copy of a vault file to the user-selected destination Uri.
     */
    fun exportDecryptedFileToUri(context: Context, file: VaultFileEntry, destinationUri: Uri) {
        val decryptedBytes = decryptFileBytes(file)
        if (decryptedBytes == null) {
            _uiState.update {
                it.copy(statusBannerMessage = "Unable to decrypt \"${file.name}\" for export")
            }
            com.example.encrypz.AppNotifications.showTransfer(context, "Download failed: unable to decrypt the selected file.", true)
            return
        }
        viewModelScope.launch {
            val written = GoogleDriveSyncManager.writeBytesToUri(context, destinationUri, decryptedBytes)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (written) {
                        "Decrypted & exported \"${file.cleanFileName}\" (${formatFileSize(decryptedBytes.size.toLong())})"
                    } else {
                        "Failed to export \"${file.cleanFileName}\""
                    }
                )
            }
            com.example.encrypz.AppNotifications.showTransfer(context,
                if (written) "Downloaded ${file.cleanFileName}" else "Download failed for ${file.cleanFileName}", !written)
        }
    }

    /**
     * Decrypts the raw payload bytes in RAM for visual previewing (images, PDFs, text, etc.).
     */
    fun decryptFileBytes(file: VaultFileEntry): ByteArray? {
        return try {
            VaultCrypto.decryptPayload(file.encryptedPayload, activeVaultKey)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypts the optional thumbnail bytes in RAM for fast Grid/List card previews.
     */
    fun decryptThumbnailBytes(file: VaultFileEntry): ByteArray? {
        val encThumb = file.encryptedThumbnail ?: return null
        return try {
            VaultCrypto.decryptPayload(encThumb, activeVaultKey)
        } catch (_: Exception) {
            null
        }
    }

    fun decryptFilePreview(file: VaultFileEntry): String {
        return try {
            val decryptedBytes = VaultCrypto.decryptPayload(file.encryptedPayload, activeVaultKey)
            val text = decryptedBytes.decodeToString()
            val printableCount = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in '!'..'~' }
            if (text.isNotEmpty() && printableCount.toFloat() / text.length >= 0.85f) {
                text
            } else {
                "Binary payload verified & decrypted in memory (${formatFileSize(file.sizeBytes)}).\nAES-256-GCM Auth Tag: VALID\n96-bit IV: 0x${file.ivHex}"
            }
        } catch (e: Exception) {
            "Unable to decrypt payload: ${e.message}"
        }
    }

    fun moveFileToFolder(fileId: String, folderId: String?, context: Context? = null) {
        if (!_uiState.value.isVaultUnlocked) return
        if (folderId != null && _uiState.value.folders.none { it.id == folderId && !it.isDeleted }) return
        _uiState.update { state -> state.copy(files = state.files.map {
            if (it.id == fileId && !it.isDeleted) it.copy(folderId = folderId) else it
        }, statusBannerMessage = "Photo moved to folder") }
        persistIfPossible(context)
    }

    fun moveFileToRecycleBin(fileId: String, context: Context? = null) {
        _uiState.update { state ->
            val target = state.files.find { it.id == fileId }
            state.copy(
                files = state.files.map { if (it.id == fileId) it.copy(isDeleted = true) else it },
                statusBannerMessage = target?.let { "Moved \"${it.name}\" to Recycle Bin" }
            )
        }
        persistIfPossible(context)
    }

    fun moveFolderToRecycleBin(folderId: String, context: Context? = null) {
        _uiState.update { state ->
            val target = state.folders.find { it.id == folderId }
            state.copy(
                folders = state.folders.map { if (it.id == folderId) it.copy(isDeleted = true) else it },
                statusBannerMessage = target?.let { "Moved folder \"${it.name}\" to Recycle Bin" }
            )
        }
        persistIfPossible(context)
    }

    fun restoreFile(fileId: String, context: Context? = null) {
        _uiState.update { state ->
            val target = state.files.find { it.id == fileId }
            state.copy(
                files = state.files.map { if (it.id == fileId) it.copy(isDeleted = false) else it },
                statusBannerMessage = target?.let { "Restored \"${it.name}\" to Vault" }
            )
        }
        persistIfPossible(context)
    }

    fun restoreFolder(folderId: String, context: Context? = null) {
        _uiState.update { state ->
            val target = state.folders.find { it.id == folderId }
            state.copy(
                folders = state.folders.map { if (it.id == folderId) it.copy(isDeleted = false) else it },
                statusBannerMessage = target?.let { "Restored folder \"${it.name}\" to Vault" }
            )
        }
        persistIfPossible(context)
    }

    fun deleteFilePermanently(fileId: String, context: Context? = null) {
        val target = _uiState.value.files.find { it.id == fileId }
        val email = _uiState.value.connectedGoogleEmail
        _uiState.update { state ->
            state.copy(
                files = state.files.filterNot { it.id == fileId },
                statusBannerMessage = "File permanently shredded from vault"
            )
        }
        persistIfPossible(context)
        if (context != null && target?.driveFileId != null) {
            viewModelScope.launch {
                GoogleDriveSyncManager.deleteEncryptedFileFromDrive(context, email, target.driveFileId)
            }
        }
    }

    fun deleteFolderPermanently(folderId: String, context: Context? = null) {
        _uiState.update { state ->
            state.copy(
                folders = state.folders.filterNot { it.id == folderId },
                statusBannerMessage = "Folder permanently deleted"
            )
        }
        persistIfPossible(context)
    }

    fun emptyRecycleBin(context: Context? = null) {
        val deletedWithDriveIds = _uiState.value.files.filter { it.isDeleted && it.driveFileId != null }
        val email = _uiState.value.connectedGoogleEmail
        _uiState.update { state ->
            state.copy(
                files = state.files.filterNot { it.isDeleted },
                folders = state.folders.filterNot { it.isDeleted },
                statusBannerMessage = "Recycle Bin emptied permanently"
            )
        }
        persistIfPossible(context)
        if (context != null && deletedWithDriveIds.isNotEmpty()) {
            viewModelScope.launch {
                deletedWithDriveIds.forEach { file ->
                    GoogleDriveSyncManager.deleteEncryptedFileFromDrive(context, email, file.driveFileId)
                }
            }
        }
    }

    fun setScreenshotProtection(enabled: Boolean) {
        _uiState.update {
            it.copy(
                screenshotProtectionEnabled = enabled,
                statusBannerMessage = if (enabled) {
                    "FLAG_SECURE enabled: screenshots & screen recording blocked"
                } else {
                    "Screenshot protection disabled for this session"
                }
            )
        }
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        _uiState.update {
            it.copy(
                autoLockTimeout = AutoLockTimeout.IMMEDIATE,
                statusBannerMessage = "Vault locks immediately when you leave the app."
            )
        }
    }

    fun exportVaultConfigJson(): String {
        val saltHex = salt.take(16).joinToString("") { "%02x".format(it) }
        val wrappedHex = wrappedVaultKey.take(24).joinToString("") { "%02x".format(it) }
        val accountLabel = _uiState.value.connectedGoogleEmail.ifBlank { "Not connected" }
        return """
            {
              "format": "encrypz-vault-config-v1",
              "cipher": "AES-256-GCM",
              "kdf": "Argon2id / PBKDF2-HMAC-SHA256",
              "iterations": 600000,
              "saltHex": "$saltHex...",
              "wrappedKeyHex": "$wrappedHex...",
              "account": "$accountLabel",
              "driveVaultFolder": "${GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME}",
              "oauthClientId": "${GoogleAuthConfig.CLIENT_ID}"
            }
        """.trimIndent()
    }

    private fun currentFormattedDate(): String {
        val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
        return formatter.format(Date())
    }

    companion object {
        private fun createInitialState(): VaultUiState = VaultUiState()
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
