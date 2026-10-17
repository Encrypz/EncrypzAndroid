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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vaultViewModel.ensureLocalStorageLoaded(context)
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
                    hasExistingVaultAccount = vaultState.hasExistingVaultAccount,
                    isCheckingAccountStatus = vaultState.isCheckingAccountStatus,
                    hasCompletedFirstLogin = vaultState.hasCompletedFirstLogin,
                    isPinSet = vaultState.isPinSet,
                    onGoogleSignInChanged = { signedIn, email ->
                        if (signedIn) {
                            vaultViewModel.connectGoogleAccount(email ?: vaultState.connectedGoogleEmail, context)
                        } else {
                            vaultViewModel.disconnectGoogleAccount()
                        }
                    },
                    onUnlockWithPassword = { password, isCreatingVault ->
                        vaultViewModel.attemptUnlockWithMasterPassword(password, isCreatingVault, context)
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
                    onLogout = {
                        isOnVaultScreen = false
                    }
                )
            }
        }
    )
}
