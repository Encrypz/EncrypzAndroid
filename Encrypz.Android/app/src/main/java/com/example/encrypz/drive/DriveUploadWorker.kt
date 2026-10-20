package com.example.encrypz.drive

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
import com.example.encrypz.data.LocalVaultStorage
import com.example.encrypz.ui.vault.DriveSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Only IDs and encrypted metadata enter WorkManager; payloads stay in private vault storage. */
class DriveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val email = inputData.getString("email") ?: return@withContext Result.failure()
        val id = inputData.getString("fileId") ?: return@withContext Result.failure()
        val name = inputData.getString("encryptedName") ?: return@withContext Result.failure()
        val context = applicationContext
        try {
            // Account selection and disk reads share the storage lock; never select an account from a worker.
            val file = synchronized(LocalVaultStorage) {
                if (!LocalVaultStorage.isSelectedAccount(context, email)) return@withContext Result.retry()
                val data = LocalVaultStorage.loadVaultData(context) ?: return@withContext Result.retry()
                data.files.find { it.id == id }
            } ?: return@withContext Result.success()
            if (file.isDeleted || file.driveSyncState == DriveSyncState.SYNCED_TO_DRIVE) {
                return@withContext Result.success()
            }
            when (val result = uploadMutex.withLock {
                GoogleDriveSyncManager.uploadEncryptedFileToDrive(
                    context, email, file, name, inputData.getString("encryptedDate").orEmpty())
            }) {
                is GoogleDriveSyncManager.DriveUploadResult.UploadedToDriveApi -> {
                    LocalVaultStorage.recordUpload(context, email, id, result.driveFileId)
                    com.example.encrypz.AppNotifications.showTransfer(context,
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
        fun enqueue(context: Context, email: String, fileId: String, encryptedName: String,
                    encryptedDate: String): String {
            val name = "drive-upload:${email.trim().lowercase(java.util.Locale.ROOT)}:$fileId"
            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setInputData(workDataOf("email" to email, "fileId" to fileId,
                    "encryptedName" to encryptedName, "encryptedDate" to encryptedDate))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            return name
        }
    }
}