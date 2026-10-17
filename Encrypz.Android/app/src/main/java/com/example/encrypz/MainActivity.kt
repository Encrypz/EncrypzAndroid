package com.example.encrypz

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.encrypz.theme.EncrypzTheme
import com.example.encrypz.ui.vault.AutoLockTimeout
import com.example.encrypz.ui.vault.VaultViewModel

class MainActivity : ComponentActivity() {
    private val vaultViewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default to FLAG_SECURE enabled to protect zero-knowledge vault from screenshots & Recents previews
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#0A0A0A"))
        )

        setContent {
            val state by vaultViewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.screenshotProtectionEnabled) {
                if (state.screenshotProtectionEnabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            EncrypzTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(vaultViewModel = vaultViewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val currentState = vaultViewModel.uiState.value
        if (currentState.isVaultUnlocked && currentState.autoLockTimeout == AutoLockTimeout.IMMEDIATE) {
            vaultViewModel.lockVault()
        }
    }
}
