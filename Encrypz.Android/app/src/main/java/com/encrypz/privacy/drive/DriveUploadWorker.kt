package com.encrypz.privacy.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.encrypz.privacy.crypto.vault.VaultPathCrypto
import com.encrypz.privacy.data.LocalVaultStorage
import com.encrypz.privacy.ui.vault.DriveSyncState
import com.encrypz.privacy.vault.GoogleDriveVaultBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Only IDs and encrypted metadata enter WorkManager; payloads stay in private vault storage. */
class DriveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val email = inputData.getString("email") ?: return@withContext Result.failure()
        val id = inputData.getString("fileId") ?: return@withContext Result.failure()
        val name = inputData.getString("encryptedName") ?: return@withContext Result.failure()
        val accountScope = inputData.getString("storageAccountId").orEmpty().ifBlank { email }
        val vaultFormat = inputData.getInt("vaultFormat", 1)
        val vaultRootFolderId = inputData.getString("vaultRootFolderId").orEmpty()
        val storageDirPath = inputData.getString("storageDirPath").orEmpty()
        val shortenedDirName = inputData.getString("shortenedDirName").orEmpty()
        val encryptedDate = inputData.getString("encryptedDate").orEmpty()
        val context = applicationContext
        try {
            // Account selection and disk reads share the storage lock; never select an account from a worker.
            val file = synchronized(LocalVaultStorage) {
                if (!LocalVaultStorage.isSelectedAccount(context, accountScope)) return@withContext Result.retry()
                val data = LocalVaultStorage.loadVaultData(context) ?: return@withContext Result.retry()
                data.files.find { it.id == id }
            } ?: return@withContext Result.success()
            if (file.isDeleted || file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                return@withContext Result.success()
            }
            if (vaultFormat == 2 && vaultRootFolderId.isNotBlank() && storageDirPath.isNotBlank()) {
                val uploadedId = uploadMutex.withLock {
                    val backend = GoogleDriveVaultBackend(context, email, vaultRootFolderId)
                    val baseSegments = storageDirPath.split('/').filter { it.isNotBlank() }
                    val appProps = if (encryptedDate.isNotBlank()) mapOf("encCreated" to encryptedDate) else emptyMap()
                    if (shortenedDirName.isNotBlank()) {
                        val shortenedSegments = baseSegments + shortenedDirName
                        backend.writeFile(
                            shortenedSegments + VaultPathCrypto.LONG_NAME_META_FILE,
                            name.toByteArray(Charsets.UTF_8)
                        )
                        backend.writeFile(
                            shortenedSegments + VaultPathCrypto.LONG_FILE_BLOB_FILE,
                            file.encryptedPayload,
                            appProps
                        )
                    } else {
                        backend.writeFile(baseSegments + name, file.encryptedPayload, appProps)
                    }
                }
                return@withContext if (!uploadedId.isNullOrBlank()) {
                    LocalVaultStorage.recordUpload(context, email, id, uploadedId)
                    com.encrypz.privacy.AppNotifications.showTransfer(
                        context,
                        "Encrypted file saved to Google Drive.",
                        false
                    )
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            when (val result = uploadMutex.withLock {
                GoogleDriveSyncManager.uploadEncryptedFileToDrive(
                    context, email, file, name, encryptedDate)
            }) {
                is GoogleDriveSyncManager.DriveUploadResult.UploadedToDriveApi -> {
                    LocalVaultStorage.recordUpload(context, email, id, result.driveFileId)
                    com.encrypz.privacy.AppNotifications.showTransfer(context,
                        "Encrypted file saved to Google Drive.", false)
                    Result.success()
                }
                is GoogleDriveSyncManager.DriveUploadResult.NeedsUserConsent -> Result.failure()
                is GoogleDriveSyncManager.DriveUploadResult.UploadFailed ->
                    if (result.retryable) Result.retry() else Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val uploadMutex = Mutex()
        fun enqueue(
            context: Context,
            email: String,
            fileId: String,
            encryptedName: String,
            encryptedDate: String,
            storageAccountId: String = email,
            vaultFormat: Int = 1,
            vaultRootFolderId: String = "",
            storageDirPath: String = "",
            shortenedDirName: String = ""
        ): String {
            val name = "drive-upload:${storageAccountId.trim().lowercase(java.util.Locale.ROOT)}:$fileId"
            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setInputData(
                    workDataOf(
                        "email" to email,
                        "fileId" to fileId,
                        "encryptedName" to encryptedName,
                        "encryptedDate" to encryptedDate,
                        "storageAccountId" to storageAccountId,
                        "vaultFormat" to vaultFormat,
                        "vaultRootFolderId" to vaultRootFolderId,
                        "storageDirPath" to storageDirPath,
                        "shortenedDirName" to shortenedDirName
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            return name
        }
    }
}
