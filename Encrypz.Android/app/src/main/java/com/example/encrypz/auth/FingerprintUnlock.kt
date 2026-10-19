package com.example.encrypz.auth

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.encrypz.ui.vault.VaultViewModel
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/** Each account has a separate, authentication-per-use key, invalidated by enrollment changes. */
private class FingerprintStore(context: Context, email: String) {
    private val prefs = context.getSharedPreferences("fingerprint_unlock_v1", Context.MODE_PRIVATE)
    private val id = MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase(java.util.Locale.ROOT).toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val alias = "encrypz_fingerprint_$id"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun enabled() = prefs.contains(id)
    fun remove() { prefs.edit().remove(id).commit(); store().deleteEntry(alias) }
    fun cipher(setup: Boolean): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (setup) {
            remove()
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true).build())
            }.generateKey()
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            val blob = Base64.decode(prefs.getString(id, null) ?: error("Set up fingerprint unlock again."), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, store().getKey(alias, null) as SecretKey,
                GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        }
        return cipher
    }
    fun save(cipher: Cipher, key: ByteArray) {
        check(prefs.edit().putString(id, Base64.encodeToString(cipher.iv + cipher.doFinal(key), Base64.NO_WRAP)).commit())
    }
    fun open(cipher: Cipher): ByteArray {
        val blob = Base64.decode(prefs.getString(id, null), Base64.NO_WRAP)
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }
}

@Composable
fun FingerprintControl(viewModel: VaultViewModel, email: String, setup: Boolean,
    autoPrompt: Boolean = false, onAutoPrompt: () -> Unit = {}, showControls: Boolean = true,
    onUnlocked: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context.fragmentActivity() ?: return
    if (email.isBlank()) return
    val storage = remember(email) { FingerprintStore(context.applicationContext, email) }
    var enabled by remember(storage) { mutableStateOf(storage.enabled()) }
    var message by remember(storage) { mutableStateOf<String?>(null) }
    var busy by remember(storage) { mutableStateOf(false) }
    var prompt by remember(storage) { mutableStateOf<BiometricPrompt?>(null) }
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    DisposableEffect(storage) { onDispose { prompt?.cancelAuthentication() } }
    if (!setup && !enabled) return
    val authenticate: () -> Unit = {
            if (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
                message = "Fingerprint unavailable. Enroll a fingerprint in Android Settings, or use your PIN or master password."
            } else {
                try {
                    val cipher = storage.cipher(setup)
                    busy = true
                    message = null
                    val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationError(code: Int, text: CharSequence) {
                                busy = false
                                message = text.toString()
                            }
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                busy = false
                                if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                                try {
                                    val authenticatedCipher = result.cryptoObject?.cipher ?: error("Authentication required")
                                    if (setup) {
                                        val key = viewModel.fingerprintSetupKey(email) ?: error("Unlock your vault before setup.")
                                        try { storage.save(authenticatedCipher, key) } finally { key.fill(0) }
                                        enabled = true
                                        message = "Fingerprint unlock enabled on this device."
                                    } else {
                                        val key = storage.open(authenticatedCipher)
                                        val unlocked = try { viewModel.unlockWithFingerprintKey(email, key, context) }
                                            finally { key.fill(0) }
                                        if (unlocked) currentOnUnlocked() else message = "Use your PIN or master password, then set up fingerprint unlock again."
                                    }
                                } catch (_: Exception) {
                                    message = "Fingerprint unlock failed. Use your PIN or master password and set it up again."
                                }
                            }
                        })
                    prompt = biometricPrompt
                    biometricPrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setTitle(if (setup) "Set up fingerprint unlock" else "Unlock Encrypz")
                        .setSubtitle(email)
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButtonText("Cancel").build(), BiometricPrompt.CryptoObject(cipher))
                } catch (_: Exception) {
                    busy = false
                    message = "Fingerprint setup has expired. Use your PIN or master password, then enable it again in Security."
                }
            }
    }
    val currentAuthenticate by rememberUpdatedState(authenticate)
    val currentOnAutoPrompt by rememberUpdatedState(onAutoPrompt)
    LaunchedEffect(storage, autoPrompt, setup) {
        if (autoPrompt && !setup && enabled) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!busy && !viewModel.uiState.value.isVaultUnlocked) {
                    currentOnAutoPrompt()
                    currentAuthenticate()
                }
                kotlinx.coroutines.awaitCancellation()
            }
        }
    }
    if (!showControls) return
    Column {
        OutlinedButton(enabled = !busy, onClick = authenticate) {
            Text(if (setup) (if (enabled) "Set up fingerprint again" else "Enable fingerprint unlock") else "Unlock with fingerprint")
        }
        if (setup && enabled) {
            TextButton(enabled = !busy, onClick = { storage.remove(); enabled = false; message = "Fingerprint unlock disabled." }) {
                Text("Disable fingerprint unlock")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
    }
}
