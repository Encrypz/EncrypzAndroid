package com.encrypz.privacy.auth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.encrypz.privacy.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException

/**
 * Google OAuth 2.0 configuration for Encrypz Android & Google Drive integration.
 * Client ID and Client Secret are injected at build time from `local.properties` via [BuildConfig].
 */
object GoogleAuthConfig {
    val CLIENT_ID: String = BuildConfig.GOOGLE_CLIENT_ID
    // Native Google authorization does not use a client secret.
    const val CLIENT_SECRET: String = ""

    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private const val PREFS_NAME = "encrypz_auth_prefs"
    private const val KEY_GOOGLE_EMAIL = "connected_google_email"

    val maskedClientId: String
        get() = if (CLIENT_ID.isNotBlank()) "${CLIENT_ID.take(18)}...googleusercontent.com" else "Not configured"

    val maskedClientSecret: String
        get() = if (CLIENT_SECRET.isNotBlank()) "${CLIENT_SECRET.take(11)}...${CLIENT_SECRET.takeLast(4)}" else "Not configured"

    @Suppress("DEPRECATION")
    fun createGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Creates a native Android Google Account Chooser intent that works across all devices
     * and signing keys without failing with DEVELOPER_ERROR (10), returning the exact
     * Google account email selected by the user in [AccountManager.KEY_ACCOUNT_NAME].
     */
    @Suppress("DEPRECATION")
    fun createGoogleAccountChooserIntent(): Intent {
        return try {
            AccountPicker.newChooseAccountIntent(
                AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .setAlwaysShowAccountPicker(true)
                    .setTitleOverrideText("Choose a Google account for Encrypz")
                    .build()
            )
        } catch (_: Exception) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        }
    }

    /**
     * Extracts the actual Google account email selected by the user from either
     * the native AccountPicker/AccountManager result or GoogleSignIn result.
     */
    @Suppress("DEPRECATION")
    fun extractSelectedEmail(context: Context, resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        // 1. Check AccountManager.KEY_ACCOUNT_NAME from AccountPicker / AccountManager
        val pickerEmail = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!pickerEmail.isNullOrBlank()) {
            saveGoogleEmail(context, pickerEmail)
            return pickerEmail
        }

        // 2. Check GoogleSignIn intent result if present
        if (data != null) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                val signInEmail = account.email
                if (!signInEmail.isNullOrBlank()) {
                    saveGoogleEmail(context, signInEmail)
                    return signInEmail
                }
            } catch (_: Exception) {
                // Ignore ApiException and check fallback below
            }
        }

        // 3. Check last signed-in Google account on the device if resultCode was OK
        if (resultCode == Activity.RESULT_OK) {
            val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
            if (!lastAccount?.email.isNullOrBlank()) {
                saveGoogleEmail(context, lastAccount!!.email!!)
                return lastAccount.email
            }
        }

        return null
    }

    fun saveGoogleEmail(context: Context, email: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOOGLE_EMAIL, email.trim())
            .apply()
    }

    fun getSavedGoogleEmail(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GOOGLE_EMAIL, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun clearSavedGoogleEmail(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_GOOGLE_EMAIL)
            .apply()
    }
}
