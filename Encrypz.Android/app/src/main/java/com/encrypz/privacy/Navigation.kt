package com.encrypz.privacy

import androidx.compose.foundation.layout.fillMaxSize
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
import com.encrypz.privacy.ui.login.LoginScreen
import com.encrypz.privacy.ui.vault.VaultListScreen
import com.encrypz.privacy.ui.vault.VaultScreen
import com.encrypz.privacy.ui.vault.VaultViewModel
import com.encrypz.privacy.vault.VaultStorageType
import kotlinx.serialization.Serializable

// Navigation Keys marked @Serializable for Navigation 3 runtime compatibility
@Serializable data object VaultList : NavKey
@Serializable data object Login : NavKey
@Serializable data object Vault : NavKey

@Composable
fun MainNavigation(
    vaultViewModel: VaultViewModel = viewModel()
) {
    var activeRoute by rememberSaveable { mutableStateOf("VaultList") }
    val backStack = remember(activeRoute) {
        mutableStateListOf<NavKey>(
            when (activeRoute) {
                "Vault" -> Vault
                "Login" -> Login
                else -> VaultList
            }
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val vaultState by vaultViewModel.uiState.collectAsStateWithLifecycle()
    val isOnVaultScreen = activeRoute == "Vault"

    androidx.compose.runtime.LaunchedEffect(
        vaultState.isVaultUnlocked,
        vaultState.isDrivePermissionGranted,
        vaultState.connectedGoogleEmail,
        vaultState.activeVault?.id
    ) {
        if (vaultState.isVaultUnlocked &&
            vaultState.activeVault?.isOfflineLocal != true &&
            vaultState.isDrivePermissionGranted
        ) {
            vaultViewModel.syncVaultWithGoogleDrive(context, notifyOnCompletion = false)
        }
    }

    val driveAccountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = com.encrypz.privacy.auth.GoogleAuthConfig.extractSelectedEmail(
            context, result.resultCode, result.data
        )
        if (email != null) {
            vaultViewModel.connectGoogleAccount(email, context, requestDrivePermissionIfNeeded = true)
        } else {
            vaultViewModel.onDriveAuthorizationLaunchFailed("Choose a Google account to enable Google Drive access.")
        }
    }

    val requestDriveAccessForEmail: (String, Boolean) -> Unit = { overrideEmail, forceBrowser ->
        val email = overrideEmail.ifBlank {
            vaultState.connectedGoogleEmail.ifBlank {
                com.encrypz.privacy.auth.GoogleAuthConfig.getSavedGoogleEmail(context).orEmpty()
            }
        }
        if (forceBrowser) {
            vaultViewModel.requestGoogleDrivePermission(
                context = context,
                emailOverride = email,
                forceBrowserOAuth = true,
                forceRetry = true
            )
        } else if (email.isBlank()) {
            try {
                driveAccountLauncher.launch(com.encrypz.privacy.auth.GoogleAuthConfig.createGoogleAccountChooserIntent())
            } catch (ex: Exception) {
                vaultViewModel.onDriveAuthorizationLaunchFailed(
                    context,
                    "Could not open Google account selection: ${ex.localizedMessage}"
                )
            }
        } else if (!vaultState.isGoogleConnected || !vaultState.connectedGoogleEmail.equals(email, ignoreCase = true)) {
            vaultViewModel.connectGoogleAccount(email, context, requestDrivePermissionIfNeeded = true)
        } else {
            vaultViewModel.requestGoogleDrivePermission(context, email, forceRetry = true)
        }
    }

    val requestDriveAccess: () -> Unit = { requestDriveAccessForEmail("", false) }
    val requestDriveAccessViaBrowser: () -> Unit = { requestDriveAccessForEmail("", true) }

    val driveAuthorizationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vaultViewModel.onDriveAuthorizationResult(
            context, result.data,
            result.resultCode == android.app.Activity.RESULT_OK
        )
    }
    androidx.compose.runtime.LaunchedEffect(vaultState.pendingDriveAuthorization) {
        vaultState.pendingDriveAuthorization?.let { pending ->
            vaultViewModel.consumeDriveAuthorization()
            vaultViewModel.markDriveAuthorizationLaunched()
            try {
                driveAuthorizationLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build()
                )
            } catch (ex: Exception) {
                vaultViewModel.onDriveAuthorizationLaunchFailed(
                    context,
                    "Could not open Google's permission screen: ${ex.localizedMessage}"
                )
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
            vaultState.pendingDriveConsentIntent?.let { consentIntent ->
                vaultViewModel.consumeDriveConsent()
                try {
                    driveConsentLauncher.launch(consentIntent)
                } catch (_: Exception) {
                    vaultViewModel.onDriveConsentResult(context, false)
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vaultViewModel.loadVaults(context)
    }

    @Composable
    fun UnlockContent() {
        val activeVault = vaultState.activeVault
        val isLocalVault = activeVault?.isOfflineLocal == true
        val biometricKeyId = activeVault?.id ?: vaultState.connectedGoogleEmail
        val biometricSubtitle = activeVault?.name ?: vaultState.connectedGoogleEmail

        // Keep this outside the PIN/password branches so cancelling never opens another prompt.
        var fingerprintPrompted by rememberSaveable(biometricKeyId) { mutableStateOf(false) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) fingerprintPrompted = false
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        com.encrypz.privacy.auth.FingerprintControl(
            viewModel = vaultViewModel,
            email = biometricKeyId,
            setup = false,
            autoPrompt = !fingerprintPrompted,
            showControls = false,
            onAutoPrompt = { fingerprintPrompted = true },
            onUnlocked = { activeRoute = "Vault" },
            subtitle = biometricSubtitle
        )
        LoginScreen(
            fingerprintContent = {
                com.encrypz.privacy.auth.FingerprintControl(
                    viewModel = vaultViewModel,
                    email = biometricKeyId,
                    setup = false,
                    onUnlocked = { activeRoute = "Vault" },
                    subtitle = biometricSubtitle
                )
            },
            vaultName = activeVault?.name.orEmpty(),
            isLocalVault = isLocalVault,
            initialGoogleSignedIn = vaultState.isGoogleConnected,
            connectedAccountEmail = vaultState.connectedGoogleEmail,
            isDrivePermissionGranted = vaultState.isDrivePermissionGranted,
            driveSetupError = vaultState.driveSetupError,
            isRequestingDrivePermission = vaultState.isRequestingDrivePermission,
            hasExistingVaultAccount = vaultState.hasExistingVaultAccount,
            isCheckingAccountStatus = vaultState.isCheckingAccountStatus,
            accountError = vaultState.statusBannerMessage,
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
            onRequestDrivePermissionViaBrowser = requestDriveAccessViaBrowser,
            onUnlockWithPassword = { password, isCreatingVault ->
                if (activeVault != null && activeVault.formatVersion == 2 && !isCreatingVault) {
                    vaultViewModel.unlockSelectedVault(context, password)
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val result = vaultViewModel.attemptUnlockWithMasterPassword(password, isCreatingVault, context)
                        if (result == com.encrypz.privacy.ui.vault.UnlockAttemptResult.SERVICE_ERROR) {
                            throw IllegalStateException(vaultViewModel.uiState.value.statusBannerMessage ?: "Unable to unlock vault.")
                        }
                        result
                    }
                }
            },
            onUnlockWithPin = { pin ->
                vaultViewModel.unlockWithPin(pin, context)
            },
            onSetupPinAfterFirstLogin = { pin ->
                vaultViewModel.setupUnlockPin(pin, context)
            },
            onBackToVaultList = {
                vaultViewModel.lockVault()
                activeRoute = "VaultList"
            },
            onRecoverWithKey = if (activeVault?.formatVersion == 2) {
                { recoveryKey, newPass ->
                    vaultViewModel.recoverVaultWithKey(context, activeVault.id, recoveryKey, newPass)
                }
            } else null,
            onLoginSuccess = {
                activeRoute = "Vault"
            }
        )
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            when {
                backStack.size > 1 -> backStack.removeLastOrNull()
                activeRoute == "Vault" -> {
                    vaultViewModel.lockVault()
                    activeRoute = "Login"
                }
                activeRoute == "Login" -> {
                    vaultViewModel.lockVault()
                    activeRoute = "VaultList"
                }
            }
        },
        entryProvider = entryProvider {
            entry<VaultList> {
                VaultListScreen(
                    viewModel = vaultViewModel,
                    onOpenVault = {
                        activeRoute = "Login"
                    },
                    onVaultCreatedAndUnlocked = {
                        activeRoute = "Vault"
                    },
                    onRequestDrivePermission = { email ->
                        requestDriveAccessForEmail(email, false)
                    },
                    onRequestDrivePermissionViaBrowser = { email ->
                        requestDriveAccessForEmail(email, true)
                    }
                )
            }
            entry<Login> {
                UnlockContent()
            }
            entry<Vault> {
                VaultScreen(
                    viewModel = vaultViewModel,
                    onRequestDrivePermission = requestDriveAccess,
                    onRequestDrivePermissionViaBrowser = requestDriveAccessViaBrowser,
                    onSwitchVault = {
                        activeRoute = "VaultList"
                    },
                    onLogout = {
                        activeRoute = "Login"
                    }
                )
            }
        }
    )

    // Keep activity-result launchers registered underneath the blocking unlock screen.
    if (isOnVaultScreen && !vaultState.isVaultUnlocked) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            )
        ) {
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                color = com.encrypz.privacy.theme.EncrypzBlack
            ) { UnlockContent() }
        }
    }
}
