package com.encrypz.privacy.ui.vault

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encrypz.privacy.auth.GoogleAuthConfig
import com.encrypz.privacy.auth.LoopbackGoogleOAuthServer
import com.encrypz.privacy.crypto.VaultCrypto
import com.encrypz.privacy.crypto.VaultFileNames
import com.encrypz.privacy.crypto.vault.VaultFileCrypto
import com.encrypz.privacy.crypto.vault.VaultMasterkey
import com.encrypz.privacy.crypto.vault.VaultPathCrypto
import com.encrypz.privacy.data.LocalVaultStorage
import com.encrypz.privacy.drive.DriveUploadWorker
import com.encrypz.privacy.drive.GoogleDriveSyncManager
import com.encrypz.privacy.vault.EncrypzVaultEngine
import com.encrypz.privacy.vault.DiscoveredDriveVault
import com.encrypz.privacy.vault.GoogleDriveVaultBackend
import com.encrypz.privacy.vault.SafVaultBackend
import com.encrypz.privacy.vault.VaultCreationResult
import com.encrypz.privacy.vault.VaultDescriptor
import com.encrypz.privacy.vault.VaultKeyRecord
import com.encrypz.privacy.vault.VaultRegistry
import com.encrypz.privacy.vault.VaultStorageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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
    SYNCING_TO_DRIVE("Syncing to Drive..."),
    SYNCED_TO_DRIVE("Saved in Google Drive"),
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
    val vaults: List<VaultDescriptor> = emptyList(),
    val activeVault: VaultDescriptor? = null,
    val discoveredDriveVaults: List<DiscoveredDriveVault> = emptyList(),
    val isDiscoveringDriveVaults: Boolean = false,
    val pendingRecoveryKeyDisplay: String? = null,
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

    private var activeMasterkey: VaultMasterkey.MasterkeySet? = null
    private var activeVaultConfig: VaultMasterkey.VaultConfig? = null
    private var activeVaultKey: SecretKey = VaultCrypto.generateVaultKey()
    private var salt: ByteArray = VaultCrypto.generateSalt()
    private var wrappedVaultKey: ByteArray = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)
    private var isStorageLoaded: Boolean = false
    private val userAuthRecords = ConcurrentHashMap<String, VaultKeyRecord>()
    private var drivePermissionJob: Job? = null
    private var driveAuthorizationLaunchedAtMs: Long = 0L
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    fun markDriveAuthorizationLaunched() {
        driveAuthorizationLaunchedAtMs = clockMs()
    }

    fun consumeDriveConsent() {
        _uiState.update { it.copy(pendingDriveConsentIntent = null) }
    }

    private fun currentScopeKey(): String =
        _uiState.value.activeVault?.id
            ?: _uiState.value.connectedGoogleEmail.trim().lowercase(Locale.ROOT).ifBlank { "default_vault_user" }

    private fun copyActiveMasterkeyOrNull(): VaultMasterkey.MasterkeySet? {
        val current = activeMasterkey ?: return null
        val enc = current.copyEncKey()
        val mac = current.copyMacKey()
        return try {
            VaultMasterkey.MasterkeySet(enc, mac)
        } finally {
            enc.fill(0)
            mac.fill(0)
        }
    }

    private fun activeBackend(context: Context, descriptor: VaultDescriptor? = _uiState.value.activeVault): EncrypzVaultEngine.VaultBackend? {
        val vault = descriptor ?: return null
        if (vault.formatVersion != 2) return null
        return when (vault.storageType) {
            VaultStorageType.LOCAL -> {
                val dir = vault.localDirPath?.let(::File) ?: VaultRegistry.defaultLocalVaultDir(context, vault.id)
                EncrypzVaultEngine.LocalVaultBackend(dir)
            }
            VaultStorageType.DEVICE_FOLDER -> {
                val treeUri = vault.deviceFolderTreeUri ?: return null
                SafVaultBackend(context, Uri.parse(treeUri))
            }
            VaultStorageType.GOOGLE_DRIVE -> {
                val rootId = vault.driveRootFolderId ?: return null
                val email = vault.googleAccountEmail.orEmpty().ifBlank { _uiState.value.connectedGoogleEmail }
                if (!email.contains("@")) return null
                GoogleDriveVaultBackend(context, email, rootId)
            }
        }
    }

    fun loadVaults(context: Context) {
        val list = VaultRegistry.listVaults(context)
        val activeId = _uiState.value.activeVault?.id ?: VaultRegistry.getActiveVaultId(context)
        val selected = list.firstOrNull { it.id == activeId }
        _uiState.update { it.copy(vaults = list, activeVault = it.activeVault ?: selected) }
    }

    fun selectVault(context: Context, vaultId: String) {
        val descriptor = VaultRegistry.getVault(context, vaultId) ?: return
        VaultRegistry.setActiveVaultId(context, descriptor.id)
        activeMasterkey?.close()
        activeMasterkey = null
        activeVaultConfig = null
        clearPreviewCache()
        isStorageLoaded = false

        val storageAccount = if (descriptor.formatVersion == 2) descriptor.id
        else descriptor.googleAccountEmail.orEmpty().ifBlank { descriptor.id }
        LocalVaultStorage.selectAccount(context, storageAccount)

        val record = LocalVaultStorage.loadUserAuthRecord(context, descriptor.id)
            ?: descriptor.googleAccountEmail?.let { LocalVaultStorage.loadUserAuthRecord(context, it) }
        if (record != null) {
            userAuthRecords[descriptor.id] = record
            descriptor.googleAccountEmail?.let { userAuthRecords[it.lowercase(Locale.ROOT)] = record }
        }

        val email = descriptor.googleAccountEmail.orEmpty()
        val isDrive = descriptor.storageType == VaultStorageType.GOOGLE_DRIVE && email.contains("@")
        _uiState.update { state ->
            state.copy(
                vaults = VaultRegistry.listVaults(context),
                activeVault = descriptor,
                isVaultUnlocked = false,
                hasExistingVaultAccount = true,
                isCheckingAccountStatus = false,
                hasCompletedFirstLogin = record?.hasLoggedInOnce == true || descriptor.formatVersion == 2,
                isPinSet = !record?.pinVerifierHex.isNullOrBlank(),
                isGoogleConnected = isDrive,
                connectedGoogleEmail = email,
                isDrivePermissionGranted = false,
                driveSetupError = null,
                currentFolderId = null,
                breadcrumbs = listOf(BreadcrumbItem(null, descriptor.name)),
                searchQuery = "",
                selectedCategory = VaultCategoryFilter.ALL,
                folders = emptyList(),
                files = emptyList(),
                statusBannerMessage = null
            )
        }
        ensureLocalStorageLoaded(context)
        if (isDrive) {
            requestGoogleDrivePermission(context, email)
        }
    }

    suspend fun createVault(
        context: Context,
        name: String,
        storageType: VaultStorageType,
        password: String,
        googleEmail: String? = null,
        deviceFolderTreeUri: String? = null
    ): Result<VaultCreationResult> = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return@withContext Result.failure(IllegalArgumentException("Enter a vault name."))
        if (password.length < 12) return@withContext Result.failure(IllegalArgumentException("Use at least 12 characters for your master password."))

        val cleanEmail = googleEmail.orEmpty().trim().lowercase(Locale.ROOT)
        if (storageType == VaultStorageType.GOOGLE_DRIVE && !cleanEmail.contains("@")) {
            return@withContext Result.failure(IllegalArgumentException("Connect a Google account for a Google Drive vault."))
        }
        val cleanTreeUri = deviceFolderTreeUri.orEmpty().trim()
        if (storageType == VaultStorageType.DEVICE_FOLDER && cleanTreeUri.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Choose a folder on your phone first."))
        }

        runCatching {
            val vaultId = UUID.randomUUID().toString()
            val today = currentFormattedDate()
            val (descriptor, backend) = when (storageType) {
                VaultStorageType.LOCAL -> {
                    val dir = VaultRegistry.defaultLocalVaultDir(context, vaultId)
                    val desc = VaultDescriptor(
                        id = vaultId,
                        name = cleanName,
                        storageType = VaultStorageType.LOCAL,
                        localDirPath = dir.absolutePath,
                        formatVersion = 2,
                        createdAt = today
                    )
                    desc to EncrypzVaultEngine.LocalVaultBackend(dir)
                }
                VaultStorageType.DEVICE_FOLDER -> {
                    val uri = Uri.parse(cleanTreeUri)
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    val desc = VaultDescriptor(
                        id = vaultId,
                        name = cleanName,
                        storageType = VaultStorageType.DEVICE_FOLDER,
                        deviceFolderTreeUri = uri.toString(),
                        formatVersion = 2,
                        createdAt = today
                    )
                    desc to SafVaultBackend(context, uri)
                }
                VaultStorageType.GOOGLE_DRIVE -> {
                    val folderId = when (
                        val prep = GoogleDriveSyncManager.prepareDriveVaultRootFolder(context, cleanEmail, cleanName)
                    ) {
                        is GoogleDriveSyncManager.DriveFolderPrepResult.Ready -> prep.folderId
                        is GoogleDriveSyncManager.DriveFolderPrepResult.AuthorizationRequired -> {
                            _uiState.update {
                                it.copy(
                                    isGoogleConnected = true,
                                    connectedGoogleEmail = cleanEmail,
                                    pendingDriveAuthorization = prep.pendingIntent,
                                    driveAuthorizationEmail = cleanEmail,
                                    isRequestingDrivePermission = true,
                                    driveSetupError = null,
                                    statusBannerMessage = "Allow Google Drive access in the popup, then tap Create Vault again."
                                )
                            }
                            throw IllegalStateException("Allow Google Drive access in the popup, then tap Create Vault again.")
                        }
                        is GoogleDriveSyncManager.DriveFolderPrepResult.ConsentRequired -> {
                            _uiState.update {
                                it.copy(
                                    pendingDriveConsentIntent = prep.consentIntent,
                                    statusBannerMessage = "Allow Google Drive access in the popup, then tap Create Vault again."
                                )
                            }
                            throw IllegalStateException("Allow Google Drive access in the popup, then tap Create Vault again.")
                        }
                        is GoogleDriveSyncManager.DriveFolderPrepResult.Failed -> {
                            _uiState.update {
                                it.copy(
                                    driveSetupError = prep.reason,
                                    statusBannerMessage = prep.reason
                                )
                            }
                            throw IllegalStateException(prep.reason)
                        }
                    }
                    val desc = VaultDescriptor(
                        id = vaultId,
                        name = cleanName,
                        storageType = VaultStorageType.GOOGLE_DRIVE,
                        googleAccountEmail = cleanEmail,
                        driveRootFolderId = folderId,
                        driveFolderName = cleanName,
                        formatVersion = 2,
                        createdAt = today
                    )
                    desc to GoogleDriveVaultBackend(context, cleanEmail, folderId)
                }
            }

            val created = EncrypzVaultEngine.initializeVault(backend, password.toCharArray())
            val updatedList = VaultRegistry.addOrUpdateVault(context, descriptor)
            VaultRegistry.setActiveVaultId(context, descriptor.id)

            LocalVaultStorage.selectAccount(context, descriptor.id)
            isStorageLoaded = true

            activeMasterkey?.close()
            activeMasterkey = created.masterkeySet
            activeVaultConfig = created.vaultConfig
            val encBytes = created.masterkeySet.copyEncKey()
            activeVaultKey = SecretKeySpec(encBytes, "AES")
            encBytes.fill(0)
            salt = VaultCrypto.generateSalt()
            wrappedVaultKey = VaultCrypto.encryptPayload(activeVaultKey.encoded, activeVaultKey)

            LocalVaultStorage.saveMasterkeyBundle(context, created.masterkeySet)
            LocalVaultStorage.saveKeyBundle(context, activeVaultKey, salt)

            val record = VaultKeyRecord(
                vaultId = descriptor.id,
                email = cleanEmail,
                passwordSaltHex = VaultCrypto.toHex(salt),
                hasLoggedInOnce = true,
                driveFolderId = descriptor.driveRootFolderId,
                updatedAt = today,
                credentialVersion = 2
            )
            LocalVaultStorage.saveUserAuthRecord(context, record)
            userAuthRecords[descriptor.id] = record
            LocalVaultStorage.saveVaultData(context, emptyList(), emptyList())

            _uiState.update { state ->
                state.copy(
                    vaults = updatedList,
                    activeVault = descriptor,
                    pendingRecoveryKeyDisplay = created.recoveryKey,
                    isGoogleConnected = storageType == VaultStorageType.GOOGLE_DRIVE,
                    connectedGoogleEmail = cleanEmail,
                    isDrivePermissionGranted = storageType == VaultStorageType.GOOGLE_DRIVE,
                    isVaultUnlocked = true,
                    hasExistingVaultAccount = true,
                    isCheckingAccountStatus = false,
                    hasCompletedFirstLogin = true,
                    isPinSet = false,
                    promptPinSetupAfterFirstLogin = true,
                    currentFolderId = null,
                    breadcrumbs = listOf(BreadcrumbItem(null, descriptor.name)),
                    folders = emptyList(),
                    files = emptyList(),
                    statusBannerMessage = "Encrypz Vault v2 \"$cleanName\" created"
                )
            }
            VaultCreationResult(descriptor = descriptor, recoveryKey = created.recoveryKey)
        }
    }

    suspend fun openExistingDeviceFolderVault(
        context: Context,
        treeUri: Uri
    ): Result<VaultDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val backend = SafVaultBackend(context, treeUri)
            val jwtBytes = backend.readFile(listOf(EncrypzVaultEngine.VAULT_CONFIG_FILE))
            val masterkeyBytes = backend.readFile(listOf(EncrypzVaultEngine.MASTERKEY_FILE))
            if (jwtBytes == null || masterkeyBytes == null) {
                throw IllegalArgumentException(
                    "No Encrypz vault found in that folder (vault.encrypz and masterkey.encrypz are required). Choose an existing vault folder or create a new vault."
                )
            }

            val config = VaultMasterkey.parseVaultConfigUnverified(jwtBytes.toString(Charsets.UTF_8))
            val existing = VaultRegistry.listVaults(context).firstOrNull {
                it.storageType == VaultStorageType.DEVICE_FOLDER && it.deviceFolderTreeUri == treeUri.toString()
            }
            val descriptor = existing ?: VaultDescriptor(
                id = config?.vaultId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = SafVaultBackend.extractFolderDisplayName(context, treeUri),
                storageType = VaultStorageType.DEVICE_FOLDER,
                deviceFolderTreeUri = treeUri.toString(),
                formatVersion = config?.format ?: 2,
                createdAt = currentFormattedDate()
            )
            val updated = VaultRegistry.addOrUpdateVault(context, descriptor)
            _uiState.update { it.copy(vaults = updated) }
            selectVault(context, descriptor.id)
            descriptor
        }
    }

    fun dismissRecoveryKeyDisplay() {
        _uiState.update { it.copy(pendingRecoveryKeyDisplay = null) }
    }

    fun getActiveRecoveryKey(): String? =
        activeMasterkey?.let { VaultMasterkey.RecoveryKeyCodec.encode(it) }

    suspend fun discoverDriveVaults(context: Context, email: String) {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        if (!cleanEmail.contains("@")) return
        _uiState.update { it.copy(isDiscoveringDriveVaults = true) }
        val found = runCatching {
            GoogleDriveSyncManager.discoverEncrypzVaults(context, cleanEmail)
        }.getOrDefault(emptyList())
        _uiState.update { it.copy(discoveredDriveVaults = found, isDiscoveringDriveVaults = false) }
    }

    fun registerExistingDriveVault(
        context: Context,
        email: String,
        discovered: DiscoveredDriveVault
    ): VaultDescriptor {
        val cleanEmail = email.trim().lowercase(Locale.ROOT)
        val existing = VaultRegistry.listVaults(context).firstOrNull {
            it.storageType == VaultStorageType.GOOGLE_DRIVE && it.driveRootFolderId == discovered.folderId
        }
        val descriptor = existing ?: VaultDescriptor(
            id = discovered.vaultId.ifBlank { "drive-${discovered.folderId}" },
            name = discovered.folderName.ifBlank { "Google Drive Vault" },
            storageType = VaultStorageType.GOOGLE_DRIVE,
            googleAccountEmail = cleanEmail,
            driveRootFolderId = discovered.folderId,
            driveFolderName = discovered.folderName,
            formatVersion = discovered.formatVersion,
            createdAt = currentFormattedDate()
        )
        val updated = VaultRegistry.addOrUpdateVault(context, descriptor)
        _uiState.update { it.copy(vaults = updated) }
        selectVault(context, descriptor.id)
        return descriptor
    }

    fun removeVault(context: Context, vaultId: String, deleteLocalFiles: Boolean = false) {
        if (_uiState.value.activeVault?.id == vaultId) {
            lockVault()
        }
        val remaining = VaultRegistry.removeVault(context, vaultId, deleteLocalFiles)
        val nextActive = remaining.firstOrNull { it.id == VaultRegistry.getActiveVaultId(context) }
        _uiState.update {
            it.copy(
                vaults = remaining,
                activeVault = if (it.activeVault?.id == vaultId) nextActive else it.activeVault
            )
        }
    }

    suspend fun unlockSelectedVault(context: Context, password: String): UnlockAttemptResult {
        val vault = _uiState.value.activeVault ?: return UnlockAttemptResult.ACCOUNT_NOT_CREATED
        if (vault.formatVersion != 2) {
            return attemptUnlockWithMasterPassword(password, isCreatingNewVault = false, context = context)
        }
        if (password.isBlank()) return UnlockAttemptResult.WRONG_PASSWORD

        return withContext(Dispatchers.IO) {
            val backend = activeBackend(context, vault) ?: run {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = "Allow Google Drive access to unlock this vault."
                    )
                }
                return@withContext UnlockAttemptResult.SERVICE_ERROR
            }
            try {
                val unlocked = EncrypzVaultEngine.unlockVault(backend, password.toCharArray())
                activeMasterkey?.close()
                activeMasterkey = unlocked.masterkeySet
                activeVaultConfig = unlocked.vaultConfig
                val encBytes = unlocked.masterkeySet.copyEncKey()
                activeVaultKey = SecretKeySpec(encBytes, "AES")
                encBytes.fill(0)

                LocalVaultStorage.selectAccount(context, vault.id)
                LocalVaultStorage.saveMasterkeyBundle(context, unlocked.masterkeySet)

                val existingRecord = userAuthRecords[vault.id] ?: LocalVaultStorage.loadUserAuthRecord(context, vault.id)
                val recordSalt = existingRecord?.passwordSaltHex
                    ?.takeIf { it.isNotBlank() }
                    ?.let { VaultCrypto.fromHex(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: VaultCrypto.generateSalt()
                salt = recordSalt

                val activeRecord = (existingRecord ?: VaultKeyRecord(
                    vaultId = vault.id,
                    email = vault.googleAccountEmail.orEmpty(),
                    passwordSaltHex = VaultCrypto.toHex(recordSalt),
                    hasLoggedInOnce = true,
                    driveFolderId = vault.driveRootFolderId,
                    updatedAt = currentFormattedDate(),
                    credentialVersion = 2
                )).copy(
                    vaultId = vault.id,
                    hasLoggedInOnce = true,
                    credentialVersion = 2
                )
                LocalVaultStorage.saveUserAuthRecord(context, activeRecord)
                userAuthRecords[vault.id] = activeRecord

                val previousLocal = LocalVaultStorage.loadVaultData(context)
                val scanned = EncrypzVaultEngine.scanVaultTree(backend, unlocked.masterkeySet, unlocked.vaultConfig)
                val (mergedFolders, mergedFiles) = mergeScannedTreeWithLocal(
                    context = context,
                    vault = vault,
                    scanned = scanned,
                    previousLocal = previousLocal,
                    masterkey = unlocked.masterkeySet
                )

                val pinConfigured = !activeRecord.pinVerifierHex.isNullOrBlank()
                _uiState.update { state ->
                    state.copy(
                        isVaultUnlocked = true,
                        hasExistingVaultAccount = true,
                        isCheckingAccountStatus = false,
                        hasCompletedFirstLogin = true,
                        isPinSet = pinConfigured,
                        promptPinSetupAfterFirstLogin = !pinConfigured,
                        folders = mergedFolders,
                        files = mergedFiles,
                        currentFolderId = null,
                        breadcrumbs = listOf(BreadcrumbItem(null, vault.name)),
                        statusBannerMessage = if (scanned.unreadableCount > 0) {
                            "Vault unlocked (${scanned.unreadableCount} unreadable item(s) skipped)"
                        } else {
                            "Vault unlocked · Encrypz Vault v2"
                        }
                    )
                }
                LocalVaultStorage.saveVaultData(context, mergedFolders, mergedFiles)
                recoverFileDates(context)
                UnlockAttemptResult.SUCCESS
            } catch (_: AEADBadTagException) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = "Wrong master password. Please enter the correct master password."
                    )
                }
                UnlockAttemptResult.WRONG_PASSWORD
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = e.message ?: "Unable to unlock vault. Please check your password and retry."
                    )
                }
                UnlockAttemptResult.SERVICE_ERROR
            }
        }
    }

    suspend fun changeMasterPassword(
        context: Context,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (newPassword.length < 12) {
            return@withContext Result.failure(IllegalArgumentException("New master password must be at least 12 characters."))
        }
        val vault = _uiState.value.activeVault
            ?: return@withContext Result.failure(IllegalStateException("No active vault selected."))
        val backend = activeBackend(context, vault)
            ?: return@withContext Result.failure(IllegalStateException("Password change requires an Encrypz Vault v2 with active storage access."))

        runCatching {
            EncrypzVaultEngine.changePassword(backend, currentPassword.toCharArray(), newPassword.toCharArray())
            _uiState.update { it.copy(statusBannerMessage = "Master password updated without re-encrypting files.") }
        }
    }

    suspend fun recoverVaultWithKey(
        context: Context,
        vaultId: String,
        recoveryKey: String,
        newPassword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (newPassword.length < 12) {
            return@withContext Result.failure(IllegalArgumentException("New master password must be at least 12 characters."))
        }
        val vault = VaultRegistry.getVault(context, vaultId)
            ?: return@withContext Result.failure(IllegalArgumentException("Vault not found."))
        val backend = activeBackend(context, vault)
            ?: return@withContext Result.failure(IllegalStateException("Connect Google Drive access for this vault first."))

        runCatching {
            val unlocked = EncrypzVaultEngine.recoverVault(
                backend = backend,
                recoveryKey = recoveryKey,
                newPassword = newPassword.toCharArray()
            )
            unlocked.masterkeySet.close()
            _uiState.update {
                it.copy(statusBannerMessage = "Vault recovered. Unlock with your new master password.")
            }
        }
    }

    private fun mergeScannedTreeWithLocal(
        context: Context,
        vault: VaultDescriptor,
        scanned: EncrypzVaultEngine.ScannedVaultTree,
        previousLocal: LocalVaultStorage.PersistedVaultData?,
        masterkey: VaultMasterkey.MasterkeySet
    ): Pair<List<VaultFolder>, List<VaultFileEntry>> {
        val localFoldersById = previousLocal?.folders?.associateBy { it.id }.orEmpty()
        val localFilesByRemote = previousLocal?.files?.filter { !it.driveFileId.isNullOrBlank() }
            ?.associateBy { it.driveFileId!! }.orEmpty()
        val localFilesByKey = previousLocal?.files?.associateBy { "${it.folderId.orEmpty()}:${it.cleanFileName}" }.orEmpty()

        val today = currentFormattedDate()
        val folders = scanned.folders.map { sf ->
            val prev = localFoldersById[sf.dirId]
            VaultFolder(
                id = sf.dirId,
                name = sf.name,
                parentFolderId = sf.parentDirId,
                createdAt = prev?.createdAt ?: today,
                ivHex = "AES-SIV",
                isDeleted = prev?.isDeleted ?: false
            )
        }
        val files = scanned.files.map { sf ->
            val prev = localFilesByRemote[sf.remoteId] ?: localFilesByKey["${sf.parentDirId.orEmpty()}:${sf.name}"]
            val decrypted = runCatching { VaultFileCrypto.decryptBytes(sf.encryptedBytes, masterkey) }.getOrNull()
            val encThumb = prev?.encryptedThumbnail ?: decrypted?.let { plain ->
                extractThumbnailFromDecryptedBytes(context, sf.name, plain)?.let { thumb ->
                    VaultFileCrypto.encryptBytes(thumb, masterkey)
                }
            }
            val createdMs = prev?.createdAtMillis
                ?: runCatching {
                    VaultCrypto.decryptPayload(VaultCrypto.fromHex(sf.encryptedCreationDate), activeVaultKey)
                        .decodeToString().toLong().takeIf { it > 0 }
                }.getOrNull()
                ?: decrypted?.let { embeddedCreationDate(it, context, sf.name) }
                ?: parseVaultDate(sf.createdTime, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            decrypted?.fill(0)

            VaultFileEntry(
                id = prev?.id ?: UUID.randomUUID().toString(),
                name = sf.name,
                folderId = sf.parentDirId,
                sizeBytes = prev?.sizeBytes ?: decrypted?.size?.toLong() ?: sf.encryptedBytes.size.toLong(),
                uploadedAt = prev?.uploadedAt ?: today,
                createdAtMillis = createdMs,
                googleAccountEmail = vault.googleAccountEmail.orEmpty(),
                encryptedPayload = sf.encryptedBytes,
                encryptedThumbnail = encThumb,
                ivHex = VaultCrypto.extractIvHex(sf.encryptedBytes),
                isDeleted = prev?.isDeleted ?: false,
                driveFileId = sf.remoteId,
                driveSyncState = if (vault.storageType == VaultStorageType.GOOGLE_DRIVE) {
                    DriveSyncState.SYNCED_TO_DRIVE
                } else {
                    DriveSyncState.LOCAL_ENCRYPTED
                }
            )
        }
        val unsyncedLocalOnly = previousLocal?.files?.filter { local ->
            local.driveFileId.isNullOrBlank() &&
                files.none { it.cleanFileName == local.cleanFileName && it.folderId == local.folderId }
        }.orEmpty()
        return folders to (unsyncedLocalOnly + files)
    }

    /**
     * Initializes persistent local storage for the vault key and encrypted blobs
     * so uploaded files survive app restarts and can be decrypted across sessions.
     */
    fun ensureLocalStorageLoaded(context: Context) {
        if (isStorageLoaded) return

        val masterkeyBundle = LocalVaultStorage.loadMasterkeyBundle(context)
        if (masterkeyBundle != null) {
            activeMasterkey?.close()
            activeMasterkey = masterkeyBundle
            val enc = masterkeyBundle.copyEncKey()
            activeVaultKey = SecretKeySpec(enc, "AES")
            enc.fill(0)
        }

        val keyBundle = LocalVaultStorage.loadOrCreateKeyBundle(context)
        if (masterkeyBundle == null) {
            activeVaultKey = keyBundle.vaultKey
        }
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
            _uiState.update { state ->
                state.copy(
                    folders = freshInitialState.folders,
                    files = freshInitialState.files
                )
            }
            LocalVaultStorage.saveVaultData(context, freshInitialState.folders, freshInitialState.files)
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
            _uiState.update {
                it.copy(
                    isRequestingDrivePermission = false,
                    pendingDriveAuthorization = null,
                    driveAuthorizationEmail = null,
                    driveSetupError = null
                )
            }
        }
        val emailKey = cleanEmail.lowercase(Locale.ROOT)
        var localRecord: VaultKeyRecord? = userAuthRecords[emailKey]
        if (context != null && _uiState.value.activeVault?.formatVersion != 2) {
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
                hasExistingVaultAccount = localRecord != null || state.activeVault != null,
                isCheckingAccountStatus = false,
                hasCompletedFirstLogin = localRecord?.hasLoggedInOnce == true || state.hasCompletedFirstLogin,
                isPinSet = !localRecord?.pinVerifierHex.isNullOrBlank() || state.isPinSet,
                statusBannerMessage = "Connected to Google ($cleanEmail)"
            )
        }
        if (context != null && _uiState.value.activeVault?.formatVersion != 2) {
            persistIfPossible(context)
        }

        if (context != null && requestDrivePermissionIfNeeded) {
            requestGoogleDrivePermission(context, cleanEmail)
        }
    }

    fun requestGoogleDrivePermission(
        context: Context,
        emailOverride: String? = null,
        onCompleted: ((Boolean) -> Unit)? = null,
        authorizationData: Intent? = null,
        forceBrowserOAuth: Boolean = false,
        forceRetry: Boolean = false
    ) {
        val targetEmail = (emailOverride ?: _uiState.value.connectedGoogleEmail)
            .trim()
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }
        val emailKey = targetEmail.lowercase(Locale.ROOT)
        val existingRecord = if (emailKey.isNotBlank()) {
            userAuthRecords[currentScopeKey()]
                ?: userAuthRecords[emailKey]
                ?: LocalVaultStorage.loadUserAuthRecord(context, emailKey)
        } else {
            null
        }

        if (_uiState.value.isRequestingDrivePermission) {
            if (!forceRetry && !forceBrowserOAuth && authorizationData == null) {
                return
            }
            drivePermissionJob?.cancel()
            LoopbackGoogleOAuthServer.cancelPendingAuthorization()
        }

        _uiState.update {
            it.copy(
                isRequestingDrivePermission = true,
                driveSetupError = null,
                pendingDriveAuthorization = null,
                statusBannerMessage = if (forceBrowserOAuth) {
                    "Opening Google Drive permission in browser..."
                } else {
                    "Requesting Google Drive permission..."
                }
            )
        }

        drivePermissionJob = viewModelScope.launch {
            try {
                val result = GoogleDriveSyncManager.requestDrivePermissionAndEnsureFolder(
                    context = context,
                    accountEmail = targetEmail,
                    fallbackRefreshToken = existingRecord?.googleRefreshToken,
                    authorizationData = authorizationData,
                    forceBrowserOAuth = forceBrowserOAuth
                )

                val currentConnected = _uiState.value.connectedGoogleEmail
                if (targetEmail.isNotBlank() &&
                    currentConnected.isNotBlank() &&
                    !currentConnected.equals(targetEmail, ignoreCase = true)
                ) {
                    _uiState.update { it.copy(isRequestingDrivePermission = false) }
                    return@launch
                }
                if (result.authorizationIntent != null && authorizationData == null) {
                    _uiState.update {
                        it.copy(
                            pendingDriveAuthorization = result.authorizationIntent,
                            driveAuthorizationEmail = targetEmail
                        )
                    }
                    return@launch
                }

                if (result.consentIntent != null) {
                    _uiState.update {
                        it.copy(
                            isRequestingDrivePermission = false,
                            pendingDriveConsentIntent = result.consentIntent
                        )
                    }
                    return@launch
                }

                val resolvedEmail = result.email.trim().ifBlank { targetEmail }
                val resolvedKey = resolvedEmail.lowercase(Locale.ROOT)

                if (result.granted && resolvedKey.isNotBlank()) {
                    val scopeKey = currentScopeKey()
                    val currentRec = userAuthRecords[scopeKey]
                        ?: LocalVaultStorage.loadUserAuthRecord(context, scopeKey)
                    if (currentRec != null) {
                        val updatedRec = currentRec.copy(
                            driveFolderId = result.folderId ?: currentRec.driveFolderId,
                            googleRefreshToken = result.refreshToken ?: currentRec.googleRefreshToken,
                            updatedAt = currentFormattedDate()
                        )
                        userAuthRecords[scopeKey] = updatedRec
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
                            "Google Drive permission granted"
                        } else {
                            result.error ?: "Google Drive permission was not granted."
                        }
                    )
                }

                if (result.granted && _uiState.value.isVaultUnlocked &&
                    _uiState.value.activeVault?.isOfflineLocal != true
                ) {
                    val unsynced = _uiState.value.files.filter {
                        !it.isDeleted && (it.driveSyncState != DriveSyncState.SYNCED_TO_DRIVE || it.driveFileId.isNullOrBlank())
                    }
                    for (file in unsynced) {
                        saveFileToGoogleDrive(context, file.id, forceDocumentSaver = false)
                    }
                    syncVaultWithGoogleDrive(context, notifyOnCompletion = false)
                }

                onCompleted?.invoke(result.granted)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                val msg = ex.message ?: "Google Drive permission failed."
                _uiState.update {
                    it.copy(
                        isRequestingDrivePermission = false,
                        isDrivePermissionGranted = false,
                        driveSetupError = msg,
                        statusBannerMessage = msg
                    )
                }
                onCompleted?.invoke(false)
            }
        }
    }

    fun consumeDriveAuthorization() {
        _uiState.update { it.copy(pendingDriveAuthorization = null) }
    }

    fun onDriveAuthorizationResult(context: Context, data: Intent?, granted: Boolean) {
        val elapsedMs = if (driveAuthorizationLaunchedAtMs > 0L) {
            (clockMs() - driveAuthorizationLaunchedAtMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        driveAuthorizationLaunchedAtMs = 0L

        val email = _uiState.value.driveAuthorizationEmail ?: run {
            _uiState.update {
                it.copy(
                    isRequestingDrivePermission = false,
                    pendingDriveAuthorization = null,
                    driveAuthorizationEmail = null
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isRequestingDrivePermission = false,
                pendingDriveAuthorization = null,
                driveAuthorizationEmail = null
            )
        }
        if (_uiState.value.connectedGoogleEmail.isNotBlank() &&
            email.isNotBlank() &&
            !_uiState.value.connectedGoogleEmail.equals(email, ignoreCase = true)
        ) {
            return
        }
        if (data != null) {
            requestGoogleDrivePermission(context, email, authorizationData = data, forceRetry = true)
        } else if (!granted &&
            elapsedMs in 0L..1499L &&
            GoogleAuthConfig.CLIENT_ID.isNotBlank() &&
            GoogleAuthConfig.CLIENT_SECRET.isNotBlank()
        ) {
            requestGoogleDrivePermission(context, email, forceBrowserOAuth = true, forceRetry = true)
        } else {
            val message = if (granted) {
                "Google returned no authorization result. Tap Allow Google Drive Access to try again."
            } else {
                "Google Drive setup was cancelled or closed. Tap Allow Google Drive Access or Authorize in Browser."
            }
            _uiState.update {
                it.copy(
                    isDrivePermissionGranted = false,
                    driveSetupError = message,
                    statusBannerMessage = message
                )
            }
        }
    }

    fun onDriveAuthorizationLaunchFailed(message: String) {
        driveAuthorizationLaunchedAtMs = 0L
        _uiState.update {
            it.copy(
                isRequestingDrivePermission = false,
                pendingDriveAuthorization = null,
                driveAuthorizationEmail = null,
                isDrivePermissionGranted = false,
                driveSetupError = message,
                statusBannerMessage = message
            )
        }
    }

    fun onDriveAuthorizationLaunchFailed(context: Context, message: String) {
        val email = _uiState.value.driveAuthorizationEmail
            ?: _uiState.value.connectedGoogleEmail
        driveAuthorizationLaunchedAtMs = 0L
        _uiState.update {
            it.copy(
                isRequestingDrivePermission = false,
                pendingDriveAuthorization = null,
                driveAuthorizationEmail = null
            )
        }
        if (GoogleAuthConfig.CLIENT_ID.isNotBlank() && GoogleAuthConfig.CLIENT_SECRET.isNotBlank()) {
            requestGoogleDrivePermission(context, email, forceBrowserOAuth = true, forceRetry = true)
        } else {
            onDriveAuthorizationLaunchFailed(message)
        }
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
     * Master-password unlock / creation for legacy vaults and in-memory unit tests.
     * For Encrypz Vault v2 vaults, use [unlockSelectedVault] or [createVault].
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
            ?: if (context != null && rawEmail.contains("@")) {
                runCatching { GoogleDriveSyncManager.findDriveAccountRecord(context, emailKey) }.getOrNull()
            } else {
                null
            }

        val activeRecord: VaultKeyRecord
        try {
            if (!isCreatingNewVault && existingRecord == null) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        hasExistingVaultAccount = false,
                        statusBannerMessage = "No vault found for $emailKey. Create a new vault first."
                    )
                }
                return UnlockAttemptResult.ACCOUNT_NOT_CREATED
            }
            if (isCreatingNewVault && existingRecord != null) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = "A vault already exists. Sign in with its master password."
                    )
                }
                return UnlockAttemptResult.SERVICE_ERROR
            }
            if (!isCreatingNewVault && existingRecord != null) {
                val recordSalt = VaultCrypto.fromHex(existingRecord.passwordSaltHex)
                val version = existingRecord.credentialVersion
                val wrapping = if (version == 2) VaultCrypto.deriveWrappingKey(password, recordSalt)
                else VaultCrypto.deriveSessionKeyFast(password, recordSalt)
                val proof = if (version == 2) VaultCrypto.accountProof(wrapping)
                else VaultCrypto.computePasswordVerifierHex(password, recordSalt)
                if (existingRecord.passwordVerifierHex.isNotBlank() &&
                    !MessageDigest.isEqual(
                        proof.lowercase().toByteArray(Charsets.UTF_8),
                        existingRecord.passwordVerifierHex.lowercase().toByteArray(Charsets.UTF_8)
                    )
                ) {
                    throw AEADBadTagException("Wrong master password.")
                }
                activeRecord = existingRecord.copy(passwordVerifierHex = proof)
            } else {
                if (password.length < 12) {
                    _uiState.update {
                        it.copy(
                            isVaultUnlocked = false,
                            statusBannerMessage = "Use at least 12 characters for your account password."
                        )
                    }
                    return UnlockAttemptResult.SERVICE_ERROR
                }
                val recordSalt = salt
                val wrapping = VaultCrypto.deriveWrappingKey(password, recordSalt)
                val proof = VaultCrypto.accountProof(wrapping)
                activeRecord = VaultKeyRecord(
                    vaultId = emailKey,
                    email = emailKey,
                    passwordSaltHex = VaultCrypto.toHex(recordSalt),
                    passwordVerifierHex = proof,
                    wrappedVaultKeyHex = VaultCrypto.toHex(VaultCrypto.encryptVaultKey(activeVaultKey, wrapping)),
                    credentialVersion = 2
                )
            }

            val recordSalt = VaultCrypto.fromHex(activeRecord.passwordSaltHex)
            val wrapping = if (activeRecord.credentialVersion == 2) VaultCrypto.deriveWrappingKey(password, recordSalt)
            else VaultCrypto.deriveSessionKeyFast(password, recordSalt)
            val restoredKey = VaultCrypto.decryptVaultKey(VaultCrypto.fromHex(activeRecord.wrappedVaultKeyHex), wrapping)

            if (context != null) {
                LocalVaultStorage.saveKeyBundle(context, restoredKey, recordSalt)
                LocalVaultStorage.saveUserAuthRecord(context, activeRecord)
                if (rawEmail.contains("@")) {
                    runCatching { GoogleDriveSyncManager.saveDriveAccountRecord(context, emailKey, activeRecord) }
                }
            }
            activeVaultKey = restoredKey
            salt = recordSalt
            wrappedVaultKey = VaultCrypto.fromHex(activeRecord.wrappedVaultKeyHex)
            userAuthRecords[emailKey] = activeRecord
        } catch (_: AEADBadTagException) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = "Wrong master password. Please enter the correct master password."
                )
            }
            return UnlockAttemptResult.WRONG_PASSWORD
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = e.message ?: "Could not verify your vault. Please retry."
                )
            }
            return UnlockAttemptResult.SERVICE_ERROR
        }

        val pinAlreadyConfigured = !activeRecord.pinVerifierHex.isNullOrBlank()
        val hasDriveAccess = _uiState.value.isDrivePermissionGranted

        _uiState.update {
            it.copy(
                isGoogleConnected = rawEmail.contains("@"),
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

        if (context != null && _uiState.value.activeVault?.isOfflineLocal != true) {
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

    fun setupUnlockPin(pin: String, context: Context? = null): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 6 || !cleanPin.all { it in '0'..'9' }) {
            return false
        }
        val scopeKey = currentScopeKey()
        val existingRecord = userAuthRecords[scopeKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, scopeKey) }
            ?: if (_uiState.value.isVaultUnlocked) {
                VaultKeyRecord(
                    vaultId = scopeKey,
                    email = _uiState.value.connectedGoogleEmail,
                    passwordSaltHex = VaultCrypto.toHex(salt),
                    hasLoggedInOnce = true,
                    credentialVersion = _uiState.value.activeVault?.formatVersion ?: 2
                )
            } else {
                return false
            }

        val recordSalt = existingRecord.passwordSaltHex
            .takeIf { it.isNotBlank() }
            ?.let { VaultCrypto.fromHex(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: salt
        val pinVerifier = VaultCrypto.computePinVerifierHex(cleanPin, recordSalt)

        val updatedRecord = existingRecord.copy(
            vaultId = scopeKey,
            passwordSaltHex = VaultCrypto.toHex(recordSalt),
            pinVerifierHex = pinVerifier,
            hasLoggedInOnce = true,
            updatedAt = currentFormattedDate()
        )

        userAuthRecords[scopeKey] = updatedRecord
        if (context != null) {
            LocalVaultStorage.saveUserAuthRecord(context, updatedRecord)
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

        val scopeKey = currentScopeKey()
        var existingRecord: VaultKeyRecord? = userAuthRecords[scopeKey]
        if (existingRecord == null && context != null) {
            existingRecord = LocalVaultStorage.loadUserAuthRecord(context, scopeKey)
            if (existingRecord != null) {
                userAuthRecords[scopeKey] = existingRecord
            }
        }

        val expectedPinVerifier = existingRecord?.pinVerifierHex
        if (existingRecord == null || !existingRecord.hasLoggedInOnce || expectedPinVerifier.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    isVaultUnlocked = false,
                    statusBannerMessage = "Please unlock with your master password first to enable PIN unlock."
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

        val activeVault = _uiState.value.activeVault
        if (activeVault?.formatVersion == 2 && context != null) {
            val loadedBundle = LocalVaultStorage.loadMasterkeyBundle(context)
            if (loadedBundle == null) {
                _uiState.update {
                    it.copy(
                        isVaultUnlocked = false,
                        statusBannerMessage = "Master password required once on this device to restore vault keys."
                    )
                }
                return false
            }
            activeMasterkey?.close()
            activeMasterkey = loadedBundle
            val enc = loadedBundle.copyEncKey()
            activeVaultKey = SecretKeySpec(enc, "AES")
            enc.fill(0)
            activeVaultConfig = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = activeVault.id)
        }

        val driveEmailForPin = _uiState.value.connectedGoogleEmail
            .ifBlank { if (activeVault?.isOfflineLocal != true) context?.let { GoogleAuthConfig.getSavedGoogleEmail(it) }.orEmpty() else "" }
        val hasDriveAccess = _uiState.value.isDrivePermissionGranted

        _uiState.update {
            it.copy(
                isGoogleConnected = driveEmailForPin.isNotBlank(),
                isDrivePermissionGranted = hasDriveAccess,
                isVaultUnlocked = true,
                hasExistingVaultAccount = true,
                hasCompletedFirstLogin = true,
                isPinSet = true,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Vault unlocked with PIN"
            )
        }

        recoverFileDates(context)
        if (context != null && driveEmailForPin.isNotBlank() && activeVault?.isOfflineLocal != true) {
            requestGoogleDrivePermission(context, driveEmailForPin)
        }
        return true
    }

    fun fingerprintSetupKey(vaultKeyId: String): ByteArray? {
        val state = _uiState.value
        val matchesVault = state.activeVault?.id == vaultKeyId ||
            state.connectedGoogleEmail.equals(vaultKeyId, ignoreCase = true)
        if (!state.isVaultUnlocked || !state.hasCompletedFirstLogin || !matchesVault) return null
        return activeMasterkey?.raw64Bytes() ?: activeVaultKey.encoded.copyOf()
    }

    fun unlockWithFingerprintKey(vaultKeyId: String, key: ByteArray, context: Context): Boolean {
        val state = _uiState.value
        val matchesVault = state.activeVault?.id == vaultKeyId ||
            state.connectedGoogleEmail.equals(vaultKeyId, ignoreCase = true)
        if (vaultKeyId.isBlank() || !matchesVault) return false

        ensureLocalStorageLoaded(context)
        val record = LocalVaultStorage.loadUserAuthRecord(context, vaultKeyId.trim().lowercase(Locale.ROOT))
            ?: LocalVaultStorage.loadUserAuthRecord(context, vaultKeyId.trim())
            ?: return false
        if (!record.hasLoggedInOnce) return false

        if (key.size == 64 && state.activeVault?.formatVersion == 2) {
            val savedMasterkey = LocalVaultStorage.loadMasterkeyBundle(context) ?: return false
            val savedBytes = savedMasterkey.raw64Bytes()
            savedMasterkey.close()
            val matches = MessageDigest.isEqual(key, savedBytes)
            savedBytes.fill(0)
            if (!matches) return false

            activeMasterkey?.close()
            activeMasterkey = VaultMasterkey.MasterkeySet(key.copyOfRange(0, 32), key.copyOfRange(32, 64))
            activeVaultKey = SecretKeySpec(key.copyOfRange(0, 32), "AES")
            activeVaultConfig = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = state.activeVault.id)
        } else if (key.size == 32) {
            if (!MessageDigest.isEqual(key, activeVaultKey.encoded)) return false
            activeVaultKey = SecretKeySpec(key.copyOf(), "AES")
        } else {
            return false
        }

        _uiState.update {
            it.copy(
                isVaultUnlocked = true,
                hasExistingVaultAccount = true,
                hasCompletedFirstLogin = true,
                promptPinSetupAfterFirstLogin = false,
                statusBannerMessage = "Vault unlocked with fingerprint"
            )
        }
        recoverFileDates(context)
        return true
    }

    fun removeUnlockPin(context: Context? = null) {
        val scopeKey = currentScopeKey()
        val existingRecord = userAuthRecords[scopeKey]
            ?: context?.let { LocalVaultStorage.loadUserAuthRecord(it, scopeKey) }
        if (existingRecord != null) {
            val updated = existingRecord.copy(pinVerifierHex = null, updatedAt = currentFormattedDate())
            userAuthRecords[scopeKey] = updated
            if (context != null) {
                LocalVaultStorage.saveUserAuthRecord(context, updated)
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

    fun onAppBackgrounded() {
        if (_uiState.value.isVaultUnlocked) lockVault()
    }

    fun lockVault() {
        activeMasterkey?.close()
        activeMasterkey = null
        clearPreviewCache()
        val rootLabel = _uiState.value.activeVault?.name ?: "My Vault"
        _uiState.update {
            it.copy(
                isVaultUnlocked = false,
                promptPinSetupAfterFirstLogin = false,
                currentFolderId = null,
                breadcrumbs = listOf(BreadcrumbItem(null, rootLabel)),
                searchQuery = "",
                selectedCategory = VaultCategoryFilter.ALL,
                statusBannerMessage = "Vault locked — keys cleared from active session"
            )
        }
    }

    private fun recoverFileDates(context: Context?) {
        _uiState.update { state ->
            state.copy(
                files = state.files.map { file ->
                    if (file.createdAtMillis != null) file else file.copy(
                        createdAtMillis = decryptFileBytes(file)?.let { plain ->
                            try { embeddedCreationDate(plain, context, file.name) } finally { plain.fill(0) }
                        } ?: file.sortDate
                    )
                }
            )
        }
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
        val folderId = UUID.randomUUID().toString()
        val parentFolderId = _uiState.value.currentFolderId
        val masterkeyCopy = copyActiveMasterkeyOrNull()
        val vault = _uiState.value.activeVault
        val vaultConfig = activeVaultConfig ?: vault?.let { VaultMasterkey.VaultConfig(vaultId = it.id) }

        val newFolder = VaultFolder(
            id = folderId,
            name = trimmed,
            parentFolderId = parentFolderId,
            createdAt = currentFormattedDate(),
            ivHex = if (masterkeyCopy != null) "AES-SIV"
            else VaultCrypto.extractIvHex(VaultCrypto.encryptPayload(trimmed.encodeToByteArray(), activeVaultKey))
        )
        _uiState.update { state ->
            state.copy(
                folders = listOf(newFolder) + state.folders,
                statusBannerMessage = "Encrypted folder \"$trimmed\" created"
            )
        }
        persistIfPossible(context)

        if (context != null && masterkeyCopy != null && vaultConfig != null && vault?.formatVersion == 2) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val backend = activeBackend(context, vault) ?: return@launch
                    EncrypzVaultEngine.createFolder(
                        backend = backend,
                        masterkey = masterkeyCopy,
                        vaultConfig = vaultConfig,
                        parentDirId = parentFolderId,
                        folderName = trimmed,
                        requestedDirId = folderId
                    )
                } catch (_: Exception) {
                } finally {
                    masterkeyCopy.close()
                }
            }
        } else {
            masterkeyCopy?.close()
        }
    }

    fun importSelectedFiles(context: Context, uris: List<Uri>) {
        val selected = uris.distinct()
        if (selected.isEmpty()) return
        if (!validImportCount(selected.size) || _uiState.value.isImportingFiles) {
            com.encrypz.privacy.AppNotifications.record(
                context,
                "Select up to $MAX_IMPORT_FILES files and wait for the current batch to finish.",
                true
            )
            return
        }
        ensureLocalStorageLoaded(context)
        val ownerKey = currentScopeKey()
        _uiState.update { it.copy(isImportingFiles = true) }
        viewModelScope.launch {
            try {
                for (uri in selected) {
                    val unlocked = _uiState.first {
                        it.isVaultUnlocked || (it.activeVault?.id ?: it.connectedGoogleEmail.lowercase(Locale.ROOT)) != ownerKey
                    }
                    if (!unlocked.isVaultUnlocked) break
                    try {
                        val (picked, sourceDate) = withContext(Dispatchers.IO) {
                            readPickedUriForVault(context, uri) to sourceFileDate(context, uri)
                        }
                        encryptAndAddFile(
                            picked.fileName,
                            picked.rawBytes,
                            picked.thumbnailBytes,
                            picked.originalSizeBytes,
                            context,
                            sourceCreatedAtMillis = sourceDate
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        com.encrypz.privacy.AppNotifications.showTransfer(
                            context,
                            "Upload skipped: ${error.message ?: "Unable to read the selected file."}",
                            true
                        )
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

        val vault = _uiState.value.activeVault
        val isEncrypzV2 = activeMasterkey != null && vault?.formatVersion == 2
        val isLocalVault = vault?.isOfflineLocal == true

        if (isEncrypzV2) {
            val masterkey = activeMasterkey!!
            val vaultConfig = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = vault!!.id)
            val cleanName = fileName.trim().ifBlank { "file_${System.currentTimeMillis()}" }
            val parentDirId = _uiState.value.currentFolderId
            val encryptedPayload = VaultFileCrypto.encryptBytes(rawBytes, masterkey)
            val encryptedThumb = thumbnailBytes?.let { VaultFileCrypto.encryptBytes(it, masterkey) }
            val finalSizeBytes = originalSizeBytes.coerceAtLeast(rawBytes.size.toLong())
            val willSyncDrive = context != null && autoSaveToGoogleDrive && !isLocalVault

            val newFile = VaultFileEntry(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                folderId = parentDirId,
                sizeBytes = finalSizeBytes,
                uploadedAt = currentFormattedDate(),
                createdAtMillis = embeddedCreationDate(rawBytes, context, cleanName)
                    ?: sourceCreatedAtMillis?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                googleAccountEmail = vault?.googleAccountEmail.orEmpty().ifBlank { vault?.name ?: "Local Vault" },
                encryptedPayload = encryptedPayload,
                encryptedThumbnail = encryptedThumb,
                ivHex = VaultCrypto.extractIvHex(encryptedPayload),
                driveSyncState = if (willSyncDrive) DriveSyncState.SYNCING_TO_DRIVE else DriveSyncState.LOCAL_ENCRYPTED
            )

            _uiState.update { state ->
                state.copy(
                    files = listOf(newFile) + state.files,
                    statusBannerMessage = if (willSyncDrive) {
                        "Encrypted with Encrypz Vault v2 & queued for Google Drive."
                    } else {
                        "Encrypted with Encrypz Vault v2 & saved locally."
                    }
                )
            }
            persistIfPossible(context)

            if (context != null && isLocalVault) {
                val masterkeyCopy = copyActiveMasterkeyOrNull()
                if (masterkeyCopy != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val backend = activeBackend(context, vault) ?: return@launch
                            val stored = EncrypzVaultEngine.writeEncryptedFile(
                                backend = backend,
                                masterkey = masterkeyCopy,
                                vaultConfig = vaultConfig,
                                parentDirId = parentDirId,
                                cleartextName = cleanName,
                                cleartextBytes = ByteArray(0),
                                encryptedPayloadOverride = encryptedPayload
                            )
                            _uiState.update { state ->
                                state.copy(
                                    files = state.files.map {
                                        if (it.id == newFile.id) it.copy(driveFileId = stored.remoteId) else it
                                    }
                                )
                            }
                            persistIfPossible(context)
                        } finally {
                            masterkeyCopy.close()
                        }
                    }
                }
            } else if (willSyncDrive && context != null) {
                saveFileToGoogleDrive(context, newFile.id, forceDocumentSaver = false)
            }
            return
        }

        // Legacy Format 1 / in-memory unit test path
        val restoredName = try {
            VaultFileNames.decryptOrLegacy(fileName, activeVaultKey)
        } catch (_: Exception) {
            _uiState.update { it.copy(statusBannerMessage = "This filename cannot be decrypted with the current vault key.") }
            return
        }
        val safeName = if (restoredName.endsWith(".enc")) restoredName else "$restoredName.enc"

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
            finalEncryptedPayload = rawBytes
            val extractedThumb = thumbnailBytes ?: if (context != null) {
                extractThumbnailFromDecryptedBytes(context, safeName, alreadyDecryptedPlaintext)
            } else {
                null
            }
            finalEncryptedThumb = extractedThumb?.let { VaultCrypto.encryptPayload(it, activeVaultKey) }
            finalSizeBytes = alreadyDecryptedPlaintext.size.toLong().coerceAtLeast(128L)
        } else {
            finalEncryptedPayload = VaultCrypto.encryptPayload(rawBytes, activeVaultKey)
            finalEncryptedThumb = thumbnailBytes?.let { VaultCrypto.encryptPayload(it, activeVaultKey) }
            finalSizeBytes = originalSizeBytes.coerceAtLeast(rawBytes.size.toLong()).coerceAtLeast(128L)
        }

        val shouldUpload = context != null && autoSaveToGoogleDrive && !isLocalVault
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
            driveSyncState = if (shouldUpload) DriveSyncState.SYNCING_TO_DRIVE else DriveSyncState.LOCAL_ENCRYPTED
        )

        _uiState.update { state ->
            state.copy(
                files = listOf(newFile) + state.files,
                statusBannerMessage = if (shouldUpload) {
                    "Encrypted file queued for automatic upload to Google Drive."
                } else {
                    "File encrypted and stored on this device."
                }
            )
        }
        persistIfPossible(context)

        if (shouldUpload && context != null) {
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
        val isEncrypzV2 = activeMasterkey != null && _uiState.value.activeVault?.formatVersion == 2
        val fileName = if (isEncrypzV2) {
            if (cleanTitle.contains('.')) cleanTitle else "$cleanTitle.txt"
        } else {
            if (cleanTitle.contains('.')) "$cleanTitle.enc" else "$cleanTitle.txt.enc"
        }
        encryptAndAddFile(
            fileName = fileName,
            rawBytes = content.encodeToByteArray(),
            context = context,
            autoSaveToGoogleDrive = autoSaveToGoogleDrive
        )
    }

    fun saveFileToGoogleDrive(context: Context, fileId: String, forceDocumentSaver: Boolean = false) {
        if (!_uiState.value.isVaultUnlocked) return
        val vault = _uiState.value.activeVault
        if (vault?.isOfflineLocal == true) return
        val file = _uiState.value.files.find { it.id == fileId && !it.isDeleted } ?: return
        val email = _uiState.value.connectedGoogleEmail
            .ifBlank { vault?.googleAccountEmail.orEmpty() }
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }
        if (email.isBlank()) return
        persistIfPossible(context)

        val encryptedCreationDateHex = VaultCrypto.toHex(
            VaultCrypto.encryptPayload(file.sortDate.toString().encodeToByteArray(), activeVaultKey)
        )

        val masterkey = activeMasterkey
        val workName = if (vault?.formatVersion == 2 && masterkey != null && !vault.driveRootFolderId.isNullOrBlank()) {
            val parentDirId = file.folderId.orEmpty()
            val storageDirPath = VaultPathCrypto.storagePathSegments(parentDirId, masterkey).joinToString("/")
            val encryptedName = VaultPathCrypto.encryptName(parentDirId, file.cleanFileName, masterkey)
            val threshold = activeVaultConfig?.maxNameLength ?: VaultMasterkey.DEFAULT_MAX_NAME_LENGTH
            val shortenedDir = VaultPathCrypto.shortenIfNeeded(encryptedName, threshold).orEmpty()
            DriveUploadWorker.enqueue(
                context = context,
                email = email,
                fileId = fileId,
                encryptedName = encryptedName,
                encryptedDate = encryptedCreationDateHex,
                storageAccountId = vault.id,
                vaultFormat = 2,
                vaultRootFolderId = vault.driveRootFolderId,
                storageDirPath = storageDirPath,
                shortenedDirName = shortenedDir
            )
        } else {
            DriveUploadWorker.enqueue(
                context = context,
                email = email,
                fileId = fileId,
                encryptedName = VaultFileNames.encrypt(file.name, activeVaultKey),
                encryptedDate = encryptedCreationDateHex
            )
        }

        _uiState.update { state ->
            state.copy(
                files = state.files.map {
                    if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.SYNCING_TO_DRIVE) else it
                },
                statusBannerMessage = "Encrypted file queued for Google Drive. Uploads retry automatically when connected."
            )
        }

        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(workName).first { work ->
                    val receipt = LocalVaultStorage.uploadedDriveId(context, email, fileId)
                    if (_uiState.value.connectedGoogleEmail.equals(email, ignoreCase = true)) {
                        if (receipt != null) {
                            _uiState.update { state ->
                                state.copy(
                                    files = state.files.map {
                                        if (it.id == fileId) it.copy(
                                            driveFileId = receipt,
                                            driveSyncState = DriveSyncState.SYNCED_TO_DRIVE
                                        ) else it
                                    }
                                )
                            }
                            persistIfPossible(context)
                        } else if (work.any { it.state == androidx.work.WorkInfo.State.FAILED }) {
                            _uiState.update { state ->
                                state.copy(
                                    files = state.files.map {
                                        if (it.id == fileId) it.copy(driveSyncState = DriveSyncState.LOCAL_ENCRYPTED) else it
                                    },
                                    statusBannerMessage = "File is safe locally. Reconnect Google Drive to resume uploads."
                                )
                            }
                        }
                    }
                    work.isNotEmpty() && work.all { it.state.isFinished }
                }
        }
    }

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
                    statusBannerMessage = "\"${file.cleanFileName}\" encrypted locally."
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
                        "Saved encrypted \"${file.cleanFileName}\" (${formatFileSize(file.encryptedPayload.size.toLong())}) to Google Drive"
                    } else {
                        "Failed to write \"${file.cleanFileName}\" to selected Google Drive location"
                    }
                )
            }
            persistIfPossible(context)
        }
    }

    fun syncVaultWithGoogleDrive(context: Context, notifyOnCompletion: Boolean = true) {
        if (!_uiState.value.isVaultUnlocked || _uiState.value.isSyncingWithDrive) return
        ensureLocalStorageLoaded(context)
        val vault = _uiState.value.activeVault
        if (vault?.isOfflineLocal == true) {
            val masterkeyCopy = copyActiveMasterkeyOrNull() ?: return
            val vaultConfig = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = vault.id)
            _uiState.update { it.copy(isSyncingWithDrive = true) }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val backend = activeBackend(context, vault) ?: return@launch
                    val previousLocal = LocalVaultStorage.loadVaultData(context)
                    val scanned = EncrypzVaultEngine.scanVaultTree(backend, masterkeyCopy, vaultConfig)
                    val (folders, files) = mergeScannedTreeWithLocal(context, vault, scanned, previousLocal, masterkeyCopy)
                    _uiState.update {
                        it.copy(
                            isSyncingWithDrive = false,
                            folders = folders,
                            files = files,
                            statusBannerMessage = "Local Encrypz vault refreshed"
                        )
                    }
                    LocalVaultStorage.saveVaultData(context, folders, files)
                } finally {
                    masterkeyCopy.close()
                    _uiState.update { it.copy(isSyncingWithDrive = false) }
                }
            }
            return
        }

        val email = _uiState.value.connectedGoogleEmail
            .ifBlank { vault?.googleAccountEmail.orEmpty() }
            .ifBlank { GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty() }

        if (email.isBlank()) {
            _uiState.update {
                it.copy(statusBannerMessage = "Sign in with your Google account to sync with Google Drive")
            }
            return
        }

        val masterkeyCopy = copyActiveMasterkeyOrNull()
        if (vault?.formatVersion == 2 && masterkeyCopy != null && !vault.driveRootFolderId.isNullOrBlank()) {
            val vaultConfig = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = vault.id)
            _uiState.update {
                it.copy(
                    isSyncingWithDrive = true,
                    statusBannerMessage = "Syncing Encrypz vault with Google Drive ($email)..."
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val backend = GoogleDriveVaultBackend(context, email, vault.driveRootFolderId)
                    val previousLocal = LocalVaultStorage.loadVaultData(context)
                    val scanned = EncrypzVaultEngine.scanVaultTree(backend, masterkeyCopy, vaultConfig)
                    if (!_uiState.value.isVaultUnlocked || _uiState.value.activeVault?.id != vault.id) return@launch
                    val (mergedFolders, mergedFiles) = mergeScannedTreeWithLocal(
                        context = context,
                        vault = vault,
                        scanned = scanned,
                        previousLocal = previousLocal,
                        masterkey = masterkeyCopy
                    )
                    _uiState.update { state ->
                        state.copy(
                            isSyncingWithDrive = false,
                            folders = mergedFolders,
                            files = mergedFiles,
                            statusBannerMessage = if (scanned.unreadableCount > 0) {
                                "Synced Drive vault (${scanned.unreadableCount} file(s) could not be decrypted)"
                            } else {
                                "Google Drive Encrypz vault is up to date (${mergedFiles.count { !it.isDeleted }} files)"
                            }
                        )
                    }
                    LocalVaultStorage.saveVaultData(context, mergedFolders, mergedFiles)
                    if (notifyOnCompletion) {
                        com.encrypz.privacy.AppNotifications.showTransfer(
                            context,
                            _uiState.value.statusBannerMessage.orEmpty(),
                            scanned.unreadableCount > 0
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isSyncingWithDrive = false,
                            statusBannerMessage = e.message ?: "Unable to sync Google Drive vault."
                        )
                    }
                } finally {
                    masterkeyCopy.close()
                }
            }
            return
        }
        masterkeyCopy?.close()

        // Legacy Format 1 Drive sync
        val syncKey = activeVaultKey
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
                            createdAtMillis = runCatching {
                                VaultCrypto.decryptPayload(
                                    VaultCrypto.fromHex(remote.encryptedCreationDate), syncKey
                                ).decodeToString().toLong().takeIf { it > 0 }
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
                        val mergedFiles = importedEntries.filter { remote ->
                            state.files.none { it.driveFileId == remote.driveFileId }
                        } + state.files
                        state.copy(
                            isSyncingWithDrive = false,
                            files = mergedFiles,
                            statusBannerMessage = when {
                                unreadable > 0 -> "Downloaded ${importedEntries.size} file(s); $unreadable file(s) could not be restored."
                                importedEntries.isNotEmpty() -> "Downloaded ${importedEntries.size} encrypted file(s) from Google Drive"
                                else -> "Google Drive Vault is up to date"
                            }
                        )
                    }
                    persistIfPossible(context)
                    if (notifyOnCompletion && (importedEntries.isNotEmpty() || unreadable > 0)) {
                        com.encrypz.privacy.AppNotifications.showTransfer(
                            context,
                            _uiState.value.statusBannerMessage.orEmpty(),
                            unreadable > 0
                        )
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

    fun exportDecryptedFileToUri(context: Context, file: VaultFileEntry, destinationUri: Uri) {
        val decryptedBytes = decryptFileBytes(file)
        if (decryptedBytes == null) {
            _uiState.update {
                it.copy(statusBannerMessage = "Unable to decrypt \"${file.cleanFileName}\" for export")
            }
            com.encrypz.privacy.AppNotifications.showTransfer(
                context,
                "Download failed: unable to decrypt the selected file.",
                true
            )
            return
        }
        viewModelScope.launch {
            val written = GoogleDriveSyncManager.writeBytesToUri(context, destinationUri, decryptedBytes)
            decryptedBytes.fill(0)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (written) {
                        "Decrypted & exported \"${file.cleanFileName}\" (${formatFileSize(file.sizeBytes)})"
                    } else {
                        "Failed to export \"${file.cleanFileName}\""
                    }
                )
            }
            com.encrypz.privacy.AppNotifications.showTransfer(
                context,
                if (written) "Downloaded ${file.cleanFileName}" else "Download failed for ${file.cleanFileName}",
                !written
            )
        }
    }

    fun decryptFileBytes(file: VaultFileEntry): ByteArray? {
        val masterkey = activeMasterkey
        if (masterkey != null) {
            runCatching { VaultFileCrypto.decryptBytes(file.encryptedPayload, masterkey) }
                .getOrNull()?.let { return it }
        }
        return try {
            VaultCrypto.decryptPayload(file.encryptedPayload, activeVaultKey)
        } catch (_: Exception) {
            null
        }
    }

    fun decryptThumbnailBytes(file: VaultFileEntry): ByteArray? {
        val encThumb = file.encryptedThumbnail ?: return null
        val masterkey = activeMasterkey
        if (masterkey != null) {
            runCatching { VaultFileCrypto.decryptBytes(encThumb, masterkey) }
                .getOrNull()?.let { return it }
        }
        return try {
            VaultCrypto.decryptPayload(encThumb, activeVaultKey)
        } catch (_: Exception) {
            null
        }
    }

    fun decryptFilePreview(file: VaultFileEntry): String {
        return try {
            val decryptedBytes = decryptFileBytes(file)
                ?: throw IllegalStateException("Decryption failed with current vault key")
            try {
                val text = decryptedBytes.decodeToString()
                val printableCount = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in '!'..'~' }
                if (text.isNotEmpty() && printableCount.toFloat() / text.length >= 0.85f) {
                    text
                } else {
                    "Binary payload verified & decrypted in memory (${formatFileSize(file.sizeBytes)}).\nAES-256-GCM Auth Tag: VALID\n96-bit IV: 0x${file.ivHex}"
                }
            } finally {
                decryptedBytes.fill(0)
            }
        } catch (e: Exception) {
            "Unable to decrypt payload: ${e.message}"
        }
    }

    fun moveFileToFolder(fileId: String, folderId: String?, context: Context? = null) {
        if (!_uiState.value.isVaultUnlocked) return
        if (folderId != null && _uiState.value.folders.none { it.id == folderId && !it.isDeleted }) return
        val targetFile = _uiState.value.files.find { it.id == fileId && !it.isDeleted } ?: return
        val oldFolderId = targetFile.folderId
        if (oldFolderId == folderId) return

        _uiState.update { state ->
            state.copy(
                files = state.files.map {
                    if (it.id == fileId && !it.isDeleted) it.copy(folderId = folderId) else it
                },
                statusBannerMessage = "Moved \"${targetFile.cleanFileName}\" to folder"
            )
        }
        persistIfPossible(context)

        val vault = _uiState.value.activeVault
        val masterkeyCopy = copyActiveMasterkeyOrNull()
        val vaultConfig = activeVaultConfig ?: vault?.let { VaultMasterkey.VaultConfig(vaultId = it.id) }
        if (context != null && masterkeyCopy != null && vaultConfig != null && vault?.formatVersion == 2) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val backend = activeBackend(context, vault) ?: return@launch
                    val moved = EncrypzVaultEngine.moveEncryptedFile(
                        backend = backend,
                        masterkey = masterkeyCopy,
                        vaultConfig = vaultConfig,
                        oldParentDirId = oldFolderId,
                        newParentDirId = folderId,
                        cleartextName = targetFile.cleanFileName,
                        encryptedPayload = targetFile.encryptedPayload,
                        oldRemoteId = targetFile.driveFileId
                    )
                    _uiState.update { state ->
                        state.copy(
                            files = state.files.map {
                                if (it.id == fileId) it.copy(driveFileId = moved.remoteId.ifBlank { it.driveFileId }) else it
                            }
                        )
                    }
                    persistIfPossible(context)
                } catch (_: Exception) {
                } finally {
                    masterkeyCopy.close()
                }
            }
        } else {
            masterkeyCopy?.close()
        }
    }

    fun moveFileToRecycleBin(fileId: String, context: Context? = null) {
        _uiState.update { state ->
            val target = state.files.find { it.id == fileId }
            state.copy(
                files = state.files.map { if (it.id == fileId) it.copy(isDeleted = true) else it },
                statusBannerMessage = target?.let { "Moved \"${it.cleanFileName}\" to Recycle Bin" }
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
                statusBannerMessage = target?.let { "Restored \"${it.cleanFileName}\" to Vault" }
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
        val vault = _uiState.value.activeVault
        _uiState.update { state ->
            state.copy(
                files = state.files.filterNot { it.id == fileId },
                statusBannerMessage = "File permanently shredded from vault"
            )
        }
        persistIfPossible(context)
        if (context != null && target != null) {
            val masterkeyCopy = copyActiveMasterkeyOrNull()
            val vaultConfig = activeVaultConfig ?: vault?.let { VaultMasterkey.VaultConfig(vaultId = it.id) }
            if (vault?.formatVersion == 2 && masterkeyCopy != null && vaultConfig != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val backend = activeBackend(context, vault) ?: return@launch
                        EncrypzVaultEngine.deleteEncryptedFile(
                            backend = backend,
                            masterkey = masterkeyCopy,
                            vaultConfig = vaultConfig,
                            parentDirId = target.folderId,
                            cleartextName = target.cleanFileName,
                            remoteId = target.driveFileId
                        )
                    } finally {
                        masterkeyCopy.close()
                    }
                }
            } else {
                masterkeyCopy?.close()
                if (target.driveFileId != null) {
                    viewModelScope.launch {
                        GoogleDriveSyncManager.deleteEncryptedFileFromDrive(context, email, target.driveFileId)
                    }
                }
            }
        }
    }

    fun deleteFolderPermanently(folderId: String, context: Context? = null) {
        val folder = _uiState.value.folders.find { it.id == folderId }
        val vault = _uiState.value.activeVault
        _uiState.update { state ->
            state.copy(
                folders = state.folders.filterNot { it.id == folderId },
                statusBannerMessage = "Folder permanently deleted"
            )
        }
        persistIfPossible(context)
        val masterkeyCopy = copyActiveMasterkeyOrNull()
        val vaultConfig = activeVaultConfig ?: vault?.let { VaultMasterkey.VaultConfig(vaultId = it.id) }
        if (context != null && folder != null && vault?.formatVersion == 2 && masterkeyCopy != null && vaultConfig != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val backend = activeBackend(context, vault) ?: return@launch
                    EncrypzVaultEngine.deleteEncryptedFolder(
                        backend = backend,
                        masterkey = masterkeyCopy,
                        vaultConfig = vaultConfig,
                        parentDirId = folder.parentFolderId,
                        folderName = folder.name,
                        dirId = folder.id
                    )
                } finally {
                    masterkeyCopy.close()
                }
            }
        } else {
            masterkeyCopy?.close()
        }
    }

    fun emptyRecycleBin(context: Context? = null) {
        val deletedFilesSnapshot = _uiState.value.files.filter { it.isDeleted }
        val deletedFoldersSnapshot = _uiState.value.folders.filter { it.isDeleted }
        val email = _uiState.value.connectedGoogleEmail
        val vault = _uiState.value.activeVault
        _uiState.update { state ->
            state.copy(
                files = state.files.filterNot { it.isDeleted },
                folders = state.folders.filterNot { it.isDeleted },
                statusBannerMessage = "Recycle Bin emptied permanently"
            )
        }
        persistIfPossible(context)
        if (context != null && (deletedFilesSnapshot.isNotEmpty() || deletedFoldersSnapshot.isNotEmpty())) {
            val masterkeyCopy = copyActiveMasterkeyOrNull()
            val vaultConfig = activeVaultConfig ?: vault?.let { VaultMasterkey.VaultConfig(vaultId = it.id) }
            if (vault?.formatVersion == 2 && masterkeyCopy != null && vaultConfig != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val backend = activeBackend(context, vault) ?: return@launch
                        for (f in deletedFilesSnapshot) {
                            EncrypzVaultEngine.deleteEncryptedFile(
                                backend, masterkeyCopy, vaultConfig, f.folderId, f.cleanFileName, f.driveFileId
                            )
                        }
                        for (dir in deletedFoldersSnapshot) {
                            EncrypzVaultEngine.deleteEncryptedFolder(
                                backend, masterkeyCopy, vaultConfig, dir.parentFolderId, dir.name, dir.id
                            )
                        }
                    } finally {
                        masterkeyCopy.close()
                    }
                }
            } else {
                masterkeyCopy?.close()
                viewModelScope.launch {
                    deletedFilesSnapshot.filter { it.driveFileId != null }.forEach { file ->
                        GoogleDriveSyncManager.deleteEncryptedFileFromDrive(context, email, file.driveFileId)
                    }
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
        val vault = _uiState.value.activeVault
        if (vault?.formatVersion == 2 && activeMasterkey != null) {
            val config = activeVaultConfig ?: VaultMasterkey.VaultConfig(vaultId = vault.id)
            val manifestJson = VaultMasterkey.signVaultManifest(config, activeMasterkey!!)
            return """
                {
                  "encrypzFormat": 2,
                  "cipherSuite": "${config.cipherSuite}",
                  "kdf": "scrypt (N=32768, r=8, p=1) + RFC 3394 AES Key Wrap",
                  "filenameCipher": "RFC 5297 AES-256-SIV (.epz / .epzl)",
                  "contentCipher": "EPZ2 64 KiB chunked AES-256-GCM",
                  "vaultId": "${config.vaultId}",
                  "vaultName": "${vault.name}",
                  "storageType": "${vault.storageType.name}",
                  "vaultManifest": $manifestJson
                }
            """.trimIndent()
        }
        val saltHex = salt.take(16).joinToString("") { "%02x".format(it) }
        val wrappedHex = wrappedVaultKey.take(24).joinToString("") { "%02x".format(it) }
        val accountLabel = _uiState.value.connectedGoogleEmail.ifBlank { "Not connected" }
        return """
            {
              "format": "encrypz-vault-config-v1",
              "cipher": "AES-256-GCM",
              "kdf": "PBKDF2-HMAC-SHA256",
              "iterations": 600000,
              "saltHex": "$saltHex...",
              "wrappedKeyHex": "$wrappedHex...",
              "account": "$accountLabel",
              "driveVaultFolder": "${GoogleDriveSyncManager.VAULT_DRIVE_FOLDER_NAME}",
              "oauthClientId": "${GoogleAuthConfig.CLIENT_ID}"
            }
        """.trimIndent()
    }

    override fun onCleared() {
        super.onCleared()
        activeMasterkey?.close()
        activeMasterkey = null
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
