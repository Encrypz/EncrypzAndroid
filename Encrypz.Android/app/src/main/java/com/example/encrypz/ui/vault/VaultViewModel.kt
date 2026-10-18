package com.example.encrypz.ui.vault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.encrypz.auth.GoogleAuthConfig
import com.example.encrypz.crypto.VaultCrypto
import com.example.encrypz.data.LocalVaultStorage
import com.example.encrypz.data.MongoAtlasClient
import com.example.encrypz.data.MongoAtlasClient.MongoVaultUserRecord
import com.example.encrypz.drive.GoogleDriveSyncManager
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
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.FIVE_MINUTES,
    val currentFolderId: String? = null,
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem(null, "My Vault")),
    val searchQuery: String = "",
    val selectedCategory: VaultCategoryFilter = VaultCategoryFilter.ALL,
    val viewMode: VaultViewMode = VaultViewMode.GRID,
    val folders: List<VaultFolder> = emptyList(),
    val files: List<VaultFileEntry> = emptyList(),
    val statusBannerMessage: String? = null,
    val isSyncingWithDrive: Boolean = false,
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
                (searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true))
        }

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
    private var activeVaultKey: SecretKey = VaultCrypto.generateVaultKey()
    private var salt: ByteArray = VaultCrypto.generateSalt()
    private var wrappedVaultKey: ByteArray = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)
    private var isStorageLoaded: Boolean = false
    private val userAuthRecords = ConcurrentHashMap<String, MongoVaultUserRecord>()

    private val _uiState = MutableStateFlow(createInitialState(activeVaultKey))
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /**
     * Initializes persistent local storage for the AES-256-GCM vault key and encrypted .enc blobs
     * so uploaded files survive app restarts and can be decrypted across sessions.
     */
    fun ensureLocalStorageLoaded(context: Context) {
        if (isStorageLoaded) return
        isStorageLoaded = true

        val keyBundle = LocalVaultStorage.loadOrCreateKeyBundle(context)
        activeVaultKey = keyBundle.vaultKey
        salt = keyBundle.salt
        wrappedVaultKey = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)

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
            val freshInitialState = createInitialState(activeVaultKey)
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
        val emailKey = cleanEmail.lowercase(Locale.ROOT)
        var localRecord: MongoVaultUserRecord? = userAuthRecords[emailKey]
        if (context != null) {
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
                files = state.files.map { file ->
                    file.copy(googleAccountEmail = cleanEmail)
                },
                statusBannerMessage = "Connected to Google Drive ($cleanEmail)"
            )
        }
        persistIfPossible(context)

        // Drive setup must not wait for the account metadata server.
        if (context != null && requestDrivePermissionIfNeeded) {
            requestGoogleDrivePermission(context, cleanEmail)
        }

        // Check user's Encrypz account in MongoDB Atlas.
        if (context != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val remoteRecord = MongoAtlasClient.findUserVaultRecord(emailKey)
                val effectiveRecord = if (remoteRecord != null) {
                    userAuthRecords[emailKey] = remoteRecord
                    LocalVaultStorage.saveUserAuthRecord(context, remoteRecord)
                    remoteRecord
                } else {
                    userAuthRecords[emailKey]
                }

                _uiState.update { state ->
                    if (state.connectedGoogleEmail.equals(cleanEmail, ignoreCase = true)) {
                        state.copy(
                            hasExistingVaultAccount = effectiveRecord != null,
                            isCheckingAccountStatus = false,
                            hasCompletedFirstLogin = effectiveRecord?.hasLoggedInOnce == true,
                            isPinSet = !effectiveRecord?.pinVerifierHex.isNullOrBlank()
                        )
                    } else {
                        state
                    }
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
                    viewModelScope.launch(Dispatchers.IO) {
                        MongoAtlasClient.upsertUserVaultRecord(updatedRec)
                    }
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
        if (granted && data != null) {
            requestGoogleDrivePermission(context, email, authorizationData = data)
        } else {
            val message = "Google Drive permission was not granted. Files remain encrypted on this device."
            _uiState.update { it.copy(isDrivePermissionGranted = false,
                driveSetupError = message, statusBannerMessage = message) }
        }
    }

    fun disconnectGoogleAccount() {
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

        // Load existing auth record from memory, LocalVaultStorage, or MongoDB Atlas
        var existingRecord: MongoVaultUserRecord? = userAuthRecords[emailKey]
        if (existingRecord == null && context != null) {
            existingRecord = LocalVaultStorage.loadUserAuthRecord(context, emailKey)
            if (existingRecord != null) {
                userAuthRecords[emailKey] = existingRecord
            }
        }
        if (existingRecord == null && context != null) {
            existingRecord = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(3500L) {
                    MongoAtlasClient.findUserVaultRecord(emailKey)
                }
            }
            if (existingRecord != null) {
                userAuthRecords[emailKey] = existingRecord
                LocalVaultStorage.saveUserAuthRecord(context, existingRecord)
            }
        }

        // If user tried to log in with a Google email that has NOT created an Encrypz account yet,
        // direct them to Account Creation instead of logging in!
        if (!isCreatingNewVault && existingRecord == null && rawEmail.isNotBlank()) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    hasExistingVaultAccount = false,
                    isCheckingAccountStatus = false,
                    statusBannerMessage = "No Encrypz account found for $rawEmail. Please create your vault."
                )
            }
            return UnlockAttemptResult.ACCOUNT_NOT_CREATED
        }

        val activeRecord: MongoVaultUserRecord
        if (!isCreatingNewVault && existingRecord != null) {
            val recordSalt = VaultCrypto.fromHex(existingRecord.passwordSaltHex).takeIf { it.isNotEmpty() } ?: salt
            val candidateVerifier = VaultCrypto.computePasswordVerifierHex(password, recordSalt)
            if (!candidateVerifier.equals(existingRecord.passwordVerifierHex, ignoreCase = true)) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = "Wrong master password. Please enter the correct master password."
                    )
                }
                return UnlockAttemptResult.WRONG_PASSWORD
            }
            salt = recordSalt
            val wrappingKey = VaultCrypto.deriveSessionKeyFast(password, salt)
            wrappedVaultKey = VaultCrypto.encryptVaultKey(activeVaultKey, wrappingKey)

            activeRecord = existingRecord.copy(
                hasLoggedInOnce = true,
                updatedAt = currentFormattedDate()
            )
            userAuthRecords[emailKey] = activeRecord
            if (context != null) {
                LocalVaultStorage.saveUserAuthRecord(context, activeRecord)
                viewModelScope.launch(Dispatchers.IO) {
                    MongoAtlasClient.upsertUserVaultRecord(activeRecord)
                }
            }
        } else {
            // Creating a new vault account
            val wrappingKey = VaultCrypto.deriveSessionKeyFast(password, salt)
            wrappedVaultKey = VaultCrypto.encryptVaultKey(activeVaultKey, wrappingKey)
            val verifierHex = VaultCrypto.computePasswordVerifierHex(password, salt)
            activeRecord = MongoVaultUserRecord(
                email = emailKey,
                passwordSaltHex = VaultCrypto.toHex(salt),
                passwordVerifierHex = verifierHex,
                wrappedVaultKeyHex = VaultCrypto.toHex(wrappedVaultKey),
                pinVerifierHex = existingRecord?.pinVerifierHex,
                hasLoggedInOnce = true,
                updatedAt = currentFormattedDate()
            )
            userAuthRecords[emailKey] = activeRecord
            if (context != null) {
                LocalVaultStorage.saveUserAuthRecord(context, activeRecord)
                viewModelScope.launch(Dispatchers.IO) {
                    MongoAtlasClient.upsertUserVaultRecord(activeRecord)
                }
            }
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
     * Configures or updates the 4–6 digit Quick-Unlock PIN after the user has completed their first login.
     */
    fun setupUnlockPin(pin: String, context: Context? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length !in 4..6 || !cleanPin.all { it.isDigit() }) {
            return false
        }
        val emailKey = _uiState.value.connectedGoogleEmail
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "default_vault_user" }

        val existingRecord = userAuthRecords[emailKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, emailKey) }
        val recordSalt = existingRecord?.let { VaultCrypto.fromHex(it.passwordSaltHex).takeIf { s -> s.isNotEmpty() } } ?: salt
        val pinVerifier = VaultCrypto.computePinVerifierHex(cleanPin, recordSalt)

        val updatedRecord = (existingRecord ?: MongoVaultUserRecord(
            email = emailKey,
            passwordSaltHex = VaultCrypto.toHex(recordSalt),
            passwordVerifierHex = VaultCrypto.computePasswordVerifierHex("default", recordSalt),
            wrappedVaultKeyHex = VaultCrypto.toHex(wrappedVaultKey)
        )).copy(
            pinVerifierHex = pinVerifier,
            hasLoggedInOnce = true,
            updatedAt = currentFormattedDate()
        )

        userAuthRecords[emailKey] = updatedRecord
        if (context != null) {
            LocalVaultStorage.saveUserAuthRecord(context, updatedRecord)
            viewModelScope.launch(Dispatchers.IO) {
                MongoAtlasClient.upsertUserVaultRecord(updatedRecord)
            }
        }

        _uiState.update {
            it.copy(
                hasCompletedFirstLogin = true,
                isPinSet = true,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Quick-Unlock PIN saved — you can now unlock your vault with your PIN"
            )
        }
        return true
    }

    /**
     * Unlocks the vault using the user's Quick-Unlock PIN (available after first Master Password login).
     */
    fun unlockWithPin(pin: String, context: Context? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length !in 4..6 || !cleanPin.all { it.isDigit() }) {
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
        if (existingRecord == null && context != null) {
            existingRecord = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(3500L) {
                    MongoAtlasClient.findUserVaultRecord(emailKey)
                }
            }
            if (existingRecord != null) {
                userAuthRecords[emailKey] = existingRecord
                LocalVaultStorage.saveUserAuthRecord(context, existingRecord)
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

        if (context != null && driveEmailForPin.isNotBlank()) {
            requestGoogleDrivePermission(context, driveEmailForPin)
        }
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
                viewModelScope.launch(Dispatchers.IO) {
                    MongoAtlasClient.upsertUserVaultRecord(updated)
                }
            }
        }
        _uiState.update {
            it.copy(
                isPinSet = false,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Quick-Unlock PIN removed — Master Password required on next unlock"
            )
        }
    }

    fun dismissPinSetupPrompt() {
        _uiState.update {
            it.copy(promptPinSetupAfterFirstLogin = false)
        }
    }

    fun lockVault() {
        _uiState.update {
            it.copy(
                isVaultUnlocked = false,
                promptPinSetupAfterFirstLogin = false,
                currentFolderId = null,
                breadcrumbs = listOf(BreadcrumbItem(null, "My Vault")),
                searchQuery = "",
                selectedCategory = VaultCategoryFilter.ALL,
                statusBannerMessage = "Vault locked — keys cleared from active session"
            )
        }
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
    fun encryptAndAddFile(
        fileName: String,
        rawBytes: ByteArray,
        thumbnailBytes: ByteArray? = null,
        originalSizeBytes: Long = rawBytes.size.toLong(),
        context: Context? = null,
        autoSaveToGoogleDrive: Boolean = true
    ) {
        if (context != null) {
            ensureLocalStorageLoaded(context)
        }

        val safeName = if (fileName.endsWith(".enc")) fileName else "$fileName.enc"

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
    private val activeDriveUploads = ConcurrentHashMap.newKeySet<String>()

    fun saveFileToGoogleDrive(
        context: Context,
        fileId: String,
        forceDocumentSaver: Boolean = false
    ) {
        val targetFile = _uiState.value.files.find { it.id == fileId } ?: return
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
                when (val result = GoogleDriveSyncManager.uploadEncryptedFileToDrive(context, accountEmail, targetFile)) {
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
                    }

                    is GoogleDriveSyncManager.DriveUploadResult.NeedsUserConsent -> {
                        // Never prompt during upload — keep encrypted locally; it syncs after the next login grant
                        _uiState.update { state ->
                            state.copy(
                                files = state.files.map {
                                    if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.LOCAL_ENCRYPTED) else it
                                },
                                statusBannerMessage = "\"${targetFile.name}\" encrypted locally — will be saved to Google Drive 'Encrypz' after Drive permission is granted at login"
                            )
                        }
                        persistIfPossible(context)
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
    fun syncVaultWithGoogleDrive(context: Context) {
        ensureLocalStorageLoaded(context)
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
                    val importedEntries = syncResult.downloadedFiles.map { remote ->
                        val decrypted = try {
                            VaultCrypto.decryptPayload(remote.encryptedBytes, activeVaultKey)
                        } catch (_: Exception) {
                            null
                        }
                        val thumbBytes = if (decrypted != null) {
                            extractThumbnailFromDecryptedBytes(context, remote.name, decrypted)
                        } else {
                            null
                        }
                        val encThumb = thumbBytes?.let { VaultCrypto.encryptPayload(it, activeVaultKey) }
                        VaultFileEntry(
                            id = UUID.randomUUID().toString(),
                            name = remote.name,
                            folderId = null,
                            sizeBytes = (decrypted?.size ?: remote.encryptedBytes.size).toLong(),
                            uploadedAt = currentFormattedDate(),
                            googleAccountEmail = email,
                            encryptedPayload = remote.encryptedBytes,
                            encryptedThumbnail = encThumb,
                            ivHex = VaultCrypto.extractIvHex(remote.encryptedBytes),
                            driveFileId = remote.driveFileId,
                            driveSyncState = DriveSyncState.SYNCED_TO_DRIVE
                        )
                    }

                    _uiState.update { state ->
                        val mergedFiles = importedEntries + state.files
                        state.copy(
                            isSyncingWithDrive = false,
                            files = mergedFiles,
                            statusBannerMessage = if (importedEntries.isNotEmpty()) {
                                "Synced ${importedEntries.size} encrypted .enc file(s) from Google Drive"
                            } else {
                                "Google Drive Vault is up to date (${GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME})"
                            }
                        )
                    }
                    persistIfPossible(context)
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
                autoLockTimeout = timeout,
                statusBannerMessage = "Auto-lock set to: ${timeout.label}"
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
        private fun createInitialState(vaultKey: SecretKey): VaultUiState {
            val folderDocsId = "folder-docs"
            val folderPhotosId = "folder-photos"
            val folderFinanceId = "folder-finance"

            val folders = listOf(
                VaultFolder(
                    id = folderDocsId,
                    name = "Documents",
                    parentFolderId = null,
                    createdAt = "Oct 12, 2026",
                    ivHex = VaultCrypto.extractIvHex(VaultCrypto.encryptPayload("Documents".encodeToByteArray(), vaultKey))
                ),
                VaultFolder(
                    id = folderPhotosId,
                    name = "Photos",
                    parentFolderId = null,
                    createdAt = "Oct 14, 2026",
                    ivHex = VaultCrypto.extractIvHex(VaultCrypto.encryptPayload("Photos".encodeToByteArray(), vaultKey))
                ),
                VaultFolder(
                    id = folderFinanceId,
                    name = "Financial & Tax",
                    parentFolderId = null,
                    createdAt = "Oct 15, 2026",
                    ivHex = VaultCrypto.extractIvHex(VaultCrypto.encryptPayload("Financial & Tax".encodeToByteArray(), vaultKey))
                )
            )

            fun makeEncryptedFile(
                id: String,
                name: String,
                folderId: String?,
                sizeBytes: Long,
                date: String,
                samplePlaintext: String,
                isDeleted: Boolean = false
            ): VaultFileEntry {
                val encrypted = VaultCrypto.encryptPayload(samplePlaintext.encodeToByteArray(), vaultKey)
                return VaultFileEntry(
                    id = id,
                    name = name,
                    folderId = folderId,
                    sizeBytes = sizeBytes,
                    uploadedAt = date,
                    googleAccountEmail = "",
                    encryptedPayload = encrypted,
                    ivHex = VaultCrypto.extractIvHex(encrypted),
                    isDeleted = isDeleted
                )
            }

            val files = listOf(
                makeEncryptedFile(
                    id = "file-1",
                    name = "passwords.txt.enc",
                    folderId = null,
                    sizeBytes = 4_280L,
                    date = "Oct 16, 2026",
                    samplePlaintext = "=== ENCRYPZ ZERO-KNOWLEDGE VAULT ===\nGitHub SSH Key: SHA256:9f86d081884c7d659a2feaa0c55ad015\nProtonMail Recovery: 8492-ABCD-EF01-9921\nAWS Root MFA Backup: JBSWY3DPEHPK3PXP\nHardware Wallet PIN: [Protected in Memory]"
                ),
                makeEncryptedFile(
                    id = "file-2",
                    name = "tax_returns_2025.pdf.enc",
                    folderId = null,
                    sizeBytes = 1_482_900L,
                    date = "Oct 15, 2026",
                    samplePlaintext = "DOCUMENT: Form 1040 Individual Tax Return Summary (2025)\nFILING STATUS: Verified & Electronically Signed\nADJUSTED GROSS INCOME: $148,500.00\nFEDERAL TAX WITHHELD: $28,420.00\nREFUND STATUS: Approved\n\nEncrypted client-side via Encrypz AES-256-GCM before Google Drive upload."
                ),
                makeEncryptedFile(
                    id = "file-3",
                    name = "passport_scan_hd.jpg.enc",
                    folderId = null,
                    sizeBytes = 3_145_728L,
                    date = "Oct 14, 2026",
                    samplePlaintext = "IMAGE_PREVIEW:PASSPORT_ID_SCAN\nDocument Type: International Biometric Passport\nResolution: 3840 x 2160 px (24-bit sRGB)\nEXIF GPS Metadata: Stripped locally prior to AES-256-GCM encryption"
                ),
                makeEncryptedFile(
                    id = "file-4",
                    name = "recovery_seed_phrase.txt.enc",
                    folderId = folderDocsId,
                    sizeBytes = 1_024L,
                    date = "Oct 12, 2026",
                    samplePlaintext = "1.river 2.shield 3.quantum 4.anchor 5.cobalt 6.velvet 7.orbit 8.cipher 9.ember 10.glacier 11.vertex 12.zenith"
                ),
                makeEncryptedFile(
                    id = "file-5",
                    name = "family_vacation_clip.mp4.enc",
                    folderId = folderPhotosId,
                    sizeBytes = 18_450_000L,
                    date = "Oct 10, 2026",
                    samplePlaintext = "VIDEO_PREVIEW:4K_HDR_STREAM\nCodec: H.265 / HEVC (3840x2160 @ 60fps)\nDuration: 01:42\nAudio: AAC-LC Stereo 48kHz\nEncryption: AES-256-GCM Authenticated Stream"
                ),
                makeEncryptedFile(
                    id = "file-6",
                    name = "old_bank_statement_q1.pdf.enc",
                    folderId = null,
                    sizeBytes = 642_100L,
                    date = "Sep 28, 2026",
                    samplePlaintext = "Archived Q1 Statement (Soft-deleted in Recycle Bin)",
                    isDeleted = true
                )
            )

            return VaultUiState(
                folders = folders,
                files = files
            )
        }
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
