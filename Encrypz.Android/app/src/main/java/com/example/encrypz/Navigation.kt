package com.example.encrypz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.example.encrypz.ui.login.LoginScreen
import com.example.encrypz.ui.vault.VaultScreen
import com.example.encrypz.ui.vault.VaultViewModel
import kotlinx.serialization.Serializable

// Navigation Keys marked @Serializable for Navigation 3 runtime compatibility
@Serializable data object Login : NavKey
@Serializable data object Vault : NavKey

@Composable
fun MainNavigation(
    vaultViewModel: VaultViewModel = viewModel()
) {
    var isOnVaultScreen by rememberSaveable { mutableStateOf(false) }
    val backStack = remember(isOnVaultScreen) {
        mutableStateListOf<NavKey>(if (isOnVaultScreen) Vault else Login)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val vaultState by vaultViewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(vaultState.statusBannerMessage) {
        vaultState.statusBannerMessage?.let { message ->
            if (AppNotifications.isTransferStatus(message)) {
                AppNotifications.show(
                    context,
                    message,
                    isError = message.contains("error", ignoreCase = true) ||
                        message.contains("unable", ignoreCase = true) ||
                        message.contains("failed", ignoreCase = true)
                )
            }
        }
    }

    val driveAccountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = com.example.encrypz.auth.GoogleAuthConfig.extractSelectedEmail(
            context, result.resultCode, result.data
        )
        if (email != null) {
            vaultViewModel.connectGoogleAccount(email, context, requestDrivePermissionIfNeeded = true)
        } else {
            vaultViewModel.onDriveAuthorizationLaunchFailed("Choose a Google account to enable automatic Drive backup.")
        }
    }
    val requestDriveAccess: () -> Unit = {
        val email = vaultState.connectedGoogleEmail.ifBlank {
            com.example.encrypz.auth.GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty()
        }
        if (email.isBlank()) {
            try {
                driveAccountLauncher.launch(com.example.encrypz.auth.GoogleAuthConfig.createGoogleAccountChooserIntent())
            } catch (ex: Exception) {
                vaultViewModel.onDriveAuthorizationLaunchFailed("Could not open Google account selection: ${ex.localizedMessage}")
            }
        } else if (!vaultState.isGoogleConnected) {
            vaultViewModel.connectGoogleAccount(email, context, requestDrivePermissionIfNeeded = true)
        } else {
            vaultViewModel.requestGoogleDrivePermission(context, email)
        }
    }

    val driveAuthorizationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vaultViewModel.onDriveAuthorizationResult(context, result.data,
            result.resultCode == android.app.Activity.RESULT_OK)
    }
    androidx.compose.runtime.LaunchedEffect(vaultState.pendingDriveAuthorization) {
        vaultState.pendingDriveAuthorization?.let { pending ->
            vaultViewModel.consumeDriveAuthorization()
            try {
                driveAuthorizationLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build()
                )
            } catch (ex: Exception) {
                vaultViewModel.onDriveAuthorizationLaunchFailed("Could not open Google's permission screen: ${ex.localizedMessage}")
            }
        }
    }

    val driveConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vaultViewModel.onDriveConsentResult(context, result.resultCode == android.app.Activity.RESULT_OK)
    }
    androidx.compose.runtime.LaunchedEffect(vaultState.pendingDriveConsentIntent, isOnVaultScreen) {
        if (!isOnVaultScreen) {
            vaultState.pendingDriveConsentIntent?.let { driveConsentLauncher.launch(it) }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vaultViewModel.ensureLocalStorageLoaded(context)
        // This marker is excluded from backup, so a fresh install starts setup again.
        val firstLaunch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(context.noBackupFilesDir, "drive_setup_started_v1").createNewFile()
        }
        if (firstLaunch) requestDriveAccess()
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            } else if (isOnVaultScreen) {
                vaultViewModel.lockVault()
                isOnVaultScreen = false
            }
        },
        entryProvider = entryProvider {
            entry<Login> {
                LoginScreen(
                    initialGoogleSignedIn = vaultState.isGoogleConnected,
                    connectedAccountEmail = vaultState.connectedGoogleEmail,
                    isDrivePermissionGranted = vaultState.isDrivePermissionGranted,
                    driveSetupError = vaultState.driveSetupError,
                    isRequestingDrivePermission = vaultState.isRequestingDrivePermission,
                    hasExistingVaultAccount = vaultState.hasExistingVaultAccount,
                    isCheckingAccountStatus = vaultState.isCheckingAccountStatus,
                    accountError = vaultState.statusBannerMessage,
                    onNotify = { message, isError -> AppNotifications.show(context, message, isError) },
                    hasCompletedFirstLogin = vaultState.hasCompletedFirstLogin,
                    isPinSet = vaultState.isPinSet,
                    onGoogleSignInChanged = { signedIn, email ->
                        if (signedIn) {
                            vaultViewModel.connectGoogleAccount(
                                email = email ?: vaultState.connectedGoogleEmail,
                                context = context,
                                requestDrivePermissionIfNeeded = true
                            )
                        } else {
                            vaultViewModel.disconnectGoogleAccount()
                        }
                    },
                    onRequestDrivePermission = requestDriveAccess,
                    onUnlockWithPassword = { password, isCreatingVault ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val result = vaultViewModel.attemptUnlockWithMasterPassword(password, isCreatingVault, context)
                            if (result == com.example.encrypz.ui.vault.UnlockAttemptResult.SERVICE_ERROR)
                                throw IllegalStateException(vaultViewModel.uiState.value.statusBannerMessage ?: "Account service unavailable.")
                            result
                        }
                    },
                    onUnlockWithPin = { pin ->
                        vaultViewModel.unlockWithPin(pin, context)
                    },
                    onSetupPinAfterFirstLogin = { pin ->
                        vaultViewModel.setupUnlockPin(pin, context)
                    },
                    onLoginSuccess = {
                        isOnVaultScreen = true
                    }
                )
            }
            entry<Vault> {
                VaultScreen(
                    viewModel = vaultViewModel,
                    onRequestDrivePermission = requestDriveAccess,
                    onLogout = {
                        isOnVaultScreen = false
                    }
                )
            }
        }
    )
}
