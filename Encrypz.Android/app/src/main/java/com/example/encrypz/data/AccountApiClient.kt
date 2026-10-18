package com.example.encrypz.data

import android.content.Context
import com.example.encrypz.BuildConfig
import com.example.encrypz.crypto.VaultCrypto
import com.example.encrypz.drive.GoogleDriveSyncManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.crypto.SecretKey

/** Calls the account backend; never connects to MongoDB from the phone. Call on IO. */
object AccountApiClient {
    class AccountError(message: String) : Exception(message)
    private fun request(token: String, path: String = "", body: JSONObject? = null): JSONObject {
        val base = BuildConfig.ACCOUNT_API_BASE_URL.trim().trimEnd('/')
        if (base.isBlank()) throw AccountError("Account server is not deployed yet. Configure its HTTPS address before creating or restoring an account.")
        val url = URL("$base/api/mobile/accounts$path")
        if (url.protocol != "https" || url.userInfo != null) throw AccountError("Account server requires an HTTPS address.")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            when (connection.responseCode) {
                200 -> return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                401 -> throw AccountError("Password or Google authorization was rejected. Check your password and Google permissions.")
                404 -> throw AccountError("Account not found. Create your Encrypz account first.")
                409 -> throw AccountError("Account already exists. Sign in with its master password.")
                429 -> throw AccountError("Too many attempts. Wait a minute and retry.")
                else -> throw AccountError("Account service unavailable. Please retry later.")
            }
        } finally { connection.disconnect() }
    }
    private fun token(context: Context, email: String): String {
        if (BuildConfig.ACCOUNT_API_BASE_URL.isBlank()) throw AccountError("Account server is not deployed yet. Configure its HTTPS address before creating or restoring an account.")
        return runBlocking { GoogleDriveSyncManager.accountAccessToken(context, email) }
    }
    fun exists(context: Context, email: String): Boolean = request(token(context, email)).getBoolean("exists")

    fun authenticate(context: Context, email: String, password: String, creating: Boolean,
                     currentKey: SecretKey, local: MongoAtlasClient.MongoVaultUserRecord?): MongoAtlasClient.MongoVaultUserRecord {
        val token = token(context, email)
        val status = request(token)
        val exists = status.getBoolean("exists")
        if (exists && creating) throw AccountError("Account already exists. Sign in with its master password.")
        var keyToWrap = currentKey
        // A legacy local account can be enrolled only after its password has been verified.
        if (!exists && (!creating || local != null)) {
            if (local == null) throw AccountError("Account not found. Create your Encrypz account first.")
            val oldSalt = VaultCrypto.fromHex(local.passwordSaltHex)
            val oldWrapping = if (local.credentialVersion == 2) VaultCrypto.deriveWrappingKey(password, oldSalt)
                else VaultCrypto.deriveSessionKeyFast(password, oldSalt)
            keyToWrap = VaultCrypto.decryptVaultKey(VaultCrypto.fromHex(local.wrappedVaultKeyHex), oldWrapping)
        }
        if (!exists && password.length < 12) throw AccountError("Use at least 12 characters for your account password.")
        val salt = if (exists) VaultCrypto.fromHex(status.getString("passwordSaltHex")) else VaultCrypto.generateSalt()
        val wrapping = VaultCrypto.deriveWrappingKey(password, salt)
        val proof = VaultCrypto.accountProof(wrapping)
        val body = JSONObject().put("proof", proof)
        if (!exists) {
            body.put("passwordSaltHex", VaultCrypto.toHex(salt))
            body.put("wrappedVaultKeyHex", VaultCrypto.toHex(VaultCrypto.encryptVaultKey(keyToWrap, wrapping)))
        }
        val result = request(token, if (exists) "/login" else "/register", body)
        if (!email.equals(result.getString("email"), ignoreCase = true)) throw AccountError("Google account does not match the selected account.")
        return MongoAtlasClient.MongoVaultUserRecord(email, result.getString("passwordSaltHex"), proof,
            result.getString("wrappedVaultKeyHex"), pinVerifierHex = local?.takeIf { it.passwordSaltHex == result.getString("passwordSaltHex") }?.pinVerifierHex,
            credentialVersion = 2)
    }
}
