package com.example.encrypz

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.encrypz.ui.login.LoginScreen
import com.example.encrypz.ui.vault.VaultScreen
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation3.runtime.NavKey

// Navigation Keys
object Login : NavKey
object Vault : NavKey

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Login)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Login> {
          LoginScreen(
            onLoginSuccess = {
                // Clear backstack and go to Vault
                backStack.clear()
                backStack.add(Vault)
            }
          )
        }
        entry<Vault> {
            VaultScreen(
                onLogout = {
                    backStack.clear()
                    backStack.add(Login)
                }
            )
        }
      },
  )
}
