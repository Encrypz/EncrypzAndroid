package com.example.encrypz.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.encrypz.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles Google OAuth 2.0 Authorization Code & Refresh Token exchange using the configured
 * Web OAuth Client ID ([GoogleAuthConfig.CLIENT_ID]) and Client Secret ([GoogleAuthConfig.CLIENT_SECRET])
 * with the registered callback `http://localhost:5207/api/Auth/google-callback`.
 *
 * This guarantees valid Google Drive REST API v3 access tokens on any Android device regardless
 * of signing keystore SHA-1 fingerprint, allowing automatic creation of the `Encrypz` folder
 * in Google Drive and direct `.enc` file uploads.
 */
object LoopbackGoogleOAuthServer {

    private const val LOOPBACK_PORT = 5207
    const val REDIRECT_URI = "http://localhost:5207/api/Auth/google-callback"
    private const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val GOOGLE_USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"

    private const val PREFS_NAME = "encrypz_oauth_tokens_prefs"
    private const val KEY_ACCESS_TOKEN_PREFIX = "access_token_"
    private const val KEY_EXPIRES_AT_PREFIX = "expires_at_"
    private const val KEY_REFRESH_TOKEN_PREFIX = "refresh_token_"

    private val oauthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    data class OAuthTokenBundle(
        val accessToken: String,
        val refreshToken: String?,
        val email: String?,
        val expiresInSeconds: Long
    )

    /**
     * Returns true if we have a non-expired access token or a saved refresh token for [email].
     */
    fun hasCachedOrRefreshToken(context: Context, email: String, fallbackRefreshTokenFromDb: String? = null): Boolean {
        val cleanEmail = email.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cachedAccess = prefs.getString("$KEY_ACCESS_TOKEN_PREFIX$cleanEmail", null)
        val expiresAt = prefs.getLong("$KEY_EXPIRES_AT_PREFIX$cleanEmail", 0L)


        if (!cachedAccess.isNullOrBlank() && now < expiresAt - 60_000L) {
            return true
        }
        val refreshToken = prefs.getString("$KEY_REFRESH_TOKEN_PREFIX$cleanEmail", null)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackRefreshTokenFromDb?.takeIf { it.isNotBlank() }
        return !refreshToken.isNullOrBlank()
    }

    /**
     * Clears the cached short-lived access token so the next call refreshes it using the refresh token.
     */
    fun clearCachedAccessToken(context: Context, email: String) {
        val cleanEmail = email.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("$KEY_ACCESS_TOKEN_PREFIX$cleanEmail")
            .remove("$KEY_EXPIRES_AT_PREFIX$cleanEmail")
            .remove("${KEY_ACCESS_TOKEN_PREFIX}default")
            .remove("${KEY_EXPIRES_AT_PREFIX}default")
            .apply()
    }

    /**
     * Returns a valid cached or refreshed OAuth 2.0 access token for [email] if one exists.
     */
    suspend fun getValidAccessTokenOrRefresh(
        context: Context,
        email: String,
        fallbackRefreshTokenFromDb: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val cachedAccess = prefs.getString("$KEY_ACCESS_TOKEN_PREFIX$cleanEmail", null)
        val expiresAt = prefs.getLong("$KEY_EXPIRES_AT_PREFIX$cleanEmail", 0L)

        val now = System.currentTimeMillis()

        if (!cachedAccess.isNullOrBlank() && now < expiresAt - 60_000L) {
            return@withContext cachedAccess
        }

        val refreshToken = prefs.getString("$KEY_REFRESH_TOKEN_PREFIX$cleanEmail", null)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackRefreshTokenFromDb?.takeIf { it.isNotBlank() }

        if (!refreshToken.isNullOrBlank()) {
            val refreshed = exchangeRefreshToken(refreshToken)
            if (refreshed != null) {
                saveTokenBundle(context, cleanEmail.ifBlank { refreshed.email.orEmpty() }, refreshed)
                return@withContext refreshed.accessToken
            }
        }
        return@withContext null
    }

    fun getSavedRefreshToken(context: Context, email: String): String? {
        val cleanEmail = email.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_REFRESH_TOKEN_PREFIX$cleanEmail", null)
    }

    fun saveTokenBundle(context: Context, email: String, bundle: OAuthTokenBundle) {
        val cleanEmail = email.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val expiresAt = System.currentTimeMillis() + (bundle.expiresInSeconds * 1000L)
        val editor = prefs.edit()
        if (cleanEmail.isNotBlank()) {
            editor.putString("$KEY_ACCESS_TOKEN_PREFIX$cleanEmail", bundle.accessToken)
            editor.putLong("$KEY_EXPIRES_AT_PREFIX$cleanEmail", expiresAt)
            if (!bundle.refreshToken.isNullOrBlank()) {
                editor.putString("$KEY_REFRESH_TOKEN_PREFIX$cleanEmail", bundle.refreshToken)
            }
        }
        editor.putString("${KEY_ACCESS_TOKEN_PREFIX}default", bundle.accessToken)
        editor.putLong("${KEY_EXPIRES_AT_PREFIX}default", expiresAt)
        if (!bundle.refreshToken.isNullOrBlank()) {
            editor.putString("${KEY_REFRESH_TOKEN_PREFIX}default", bundle.refreshToken)
        }
        editor.apply()
    }

    /**
     * Starts the local loopback listener on port `5207` (accepting both IPv4 `127.0.0.1` and IPv6 `::1`),
     * launches the Google OAuth 2.0 consent page in the user's browser, waits for
     * `/api/Auth/google-callback?code=...`, and exchanges the authorization code with
     * [GoogleAuthConfig.CLIENT_ID] and [GoogleAuthConfig.CLIENT_SECRET] for a live Google Drive
     * `access_token` and `refresh_token`.
     */
    suspend fun authorizeViaLoopbackBrowser(
        context: Context,
        loginHintEmail: String?
    ): OAuthTokenBundle? = authMutex.withLock {
        // If another (concurrent) login step already obtained permission, reuse it — never prompt twice.
        val hint = loginHintEmail?.trim().orEmpty()
        if (hint.contains("@")) {
            val existing = getValidAccessTokenOrRefresh(context, hint)
            if (!existing.isNullOrBlank()) {
                return@withLock OAuthTokenBundle(
                    accessToken = existing,
                    refreshToken = getSavedRefreshToken(context, hint),
                    email = hint,
                    expiresInSeconds = 3000L
                )
            }
        }
        authorizeViaLoopbackBrowserInternal(context, loginHintEmail)
    }

    private suspend fun authorizeViaLoopbackBrowserInternal(
        context: Context,
        loginHintEmail: String?
    ): OAuthTokenBundle? = withContext(oauthScope.coroutineContext) {
        val appContext = context.applicationContext
        if (GoogleAuthConfig.CLIENT_ID.isBlank() || GoogleAuthConfig.CLIENT_SECRET.isBlank()) {
            return@withContext null
        }

        try {
            activeServerSocket?.close()
        } catch (_: Exception) {
        }

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LOOPBACK_PORT), 50)
                soTimeout = 180_000 // 3 minutes timeout for user to tap Allow
            }
        } catch (_: Exception) {
            return@withContext null
        }
        activeServerSocket = server

        try {
            // Generate PKCE code_verifier & code_challenge (S256)
            val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val codeVerifier = Base64.encodeToString(
                verifierBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val challengeBytes = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            val codeChallenge = Base64.encodeToString(
                challengeBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val scopes = listOf(
                "openid",
                "email",
                "profile",
                GoogleAuthConfig.SCOPE_DRIVE_FILE,
                GoogleAuthConfig.SCOPE_DRIVE_APPDATA
            ).joinToString(" ")

            val hasHint = !loginHintEmail.isNullOrBlank() && loginHintEmail.contains("@")
            val promptParam = if (hasHint) "consent" else "select_account consent"

            val authUrlBuilder = StringBuilder(GOOGLE_AUTH_ENDPOINT)
                .append("?client_id=").append(URLEncoder.encode(GoogleAuthConfig.CLIENT_ID, "UTF-8"))
                .append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
                .append("&response_type=code")
                .append("&scope=").append(URLEncoder.encode(scopes, "UTF-8"))
                .append("&access_type=offline")
                .append("&include_granted_scopes=true")
                .append("&prompt=").append(URLEncoder.encode(promptParam, "UTF-8"))
                .append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
                .append("&code_challenge_method=S256")

            if (hasHint) {
                authUrlBuilder.append("&login_hint=")
                    .append(URLEncoder.encode(loginHintEmail!!.trim(), "UTF-8"))
            }

            val authCodeDeferred = CompletableDeferred<String?>()

            // Accept incoming loopback connections concurrently so Chrome preconnect sockets never block
            val acceptJob = launch(Dispatchers.IO) {
                while (isActive && !authCodeDeferred.isCompleted) {
                    val clientSocket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        try {
                            clientSocket.use { socket ->
                                socket.soTimeout = 8_000
                                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                                val requestLine = reader.readLine() ?: return@use
                                // Drain HTTP request headers until blank line to prevent TCP RST on socket close
                                while (true) {
                                    val headerLine = reader.readLine() ?: break
                                    if (headerLine.isEmpty()) break
                                }

                                val pathPart = requestLine.split(" ").getOrNull(1) ?: ""
                                if (pathPart.startsWith("/favicon.ico")) {
                                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    socket.getOutputStream().write(notFound.toByteArray(Charsets.UTF_8))
                                    socket.getOutputStream().flush()
                                    return@use
                                }

                                val query = pathPart.substringAfter('?', "")
                                val params = parseQueryParams(query)
                                val code = params["code"]
                                val error = params["error"]

                                val htmlBody = buildCompletionHtml(success = !code.isNullOrBlank())
                                val htmlBytes = htmlBody.toByteArray(Charsets.UTF_8)
                                val httpResponse = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: text/html; charset=UTF-8\r\n")
                                    append("Cache-Control: no-store\r\n")
                                    append("Content-Length: ${htmlBytes.size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                val out = socket.getOutputStream()
                                out.write(httpResponse.toByteArray(Charsets.UTF_8))
                                out.write(htmlBytes)
                                out.flush()

                                if (!code.isNullOrBlank()) {
                                    authCodeDeferred.complete(code)
                                    try {
                                        server.close()
                                    } catch (_: Exception) {
                                    }
                                } else if (!error.isNullOrBlank()) {
                                    authCodeDeferred.complete(null)
                                    try {
                                        server.close()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore individual preconnect/aborted socket errors
                        }
                    }
                }
            }

            // Open the Google OAuth 2.0 permission screen in the user's browser on the main thread
            withContext(Dispatchers.Main) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrlBuilder.toString())).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(browserIntent)
            }

            val finalCode = withTimeoutOrNull(180_000L) {
                authCodeDeferred.await()
            }
            acceptJob.cancel()

            // Bring Encrypz MainActivity back to foreground immediately
            withContext(Dispatchers.Main) {
                try {
                    val resumeIntent = Intent(appContext, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    appContext.startActivity(resumeIntent)
                } catch (_: Exception) {
                }
            }

            if (finalCode.isNullOrBlank()) return@withContext null

            val bundle = exchangeCodeForTokens(finalCode, codeVerifier) ?: return@withContext null
            val targetEmail = bundle.email
                ?.takeIf { it.isNotBlank() }
                ?: fetchEmailFromUserInfo(bundle.accessToken)
                ?: loginHintEmail.orEmpty()

            val finalBundle = bundle.copy(email = targetEmail.ifBlank { bundle.email })
            if (targetEmail.isNotBlank()) {
                GoogleAuthConfig.saveGoogleEmail(appContext, targetEmail)
            }
            saveTokenBundle(appContext, targetEmail, finalBundle)
            return@withContext finalBundle
        } catch (_: Exception) {
            return@withContext null
        } finally {
            try {
                server.close()
            } catch (_: Exception) {
            }
            if (activeServerSocket === server) {
                activeServerSocket = null
            }
        }
    }

    private fun exchangeCodeForTokens(code: String, codeVerifier: String): OAuthTokenBundle? {
        val formBody = buildString {
            append("code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(GoogleAuthConfig.CLIENT_ID, "UTF-8"))
            append("&client_secret=").append(URLEncoder.encode(GoogleAuthConfig.CLIENT_SECRET, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(URLEncoder.encode(codeVerifier, "UTF-8"))
        }
        return executeTokenPost(formBody, existingRefreshToken = null)
    }

    private fun exchangeRefreshToken(refreshToken: String): OAuthTokenBundle? {
        val formBody = buildString {
            append("refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(GoogleAuthConfig.CLIENT_ID, "UTF-8"))
            append("&client_secret=").append(URLEncoder.encode(GoogleAuthConfig.CLIENT_SECRET, "UTF-8"))
            append("&grant_type=refresh_token")
        }
        return executeTokenPost(formBody, existingRefreshToken = refreshToken)
    }

    private fun executeTokenPost(formBody: String, existingRefreshToken: String?): OAuthTokenBundle? {
        return try {
            val conn = (URL(GOOGLE_TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.outputStream.use { out ->
                out.write(formBody.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) return null

            val json = JSONObject(body)
            val accessToken = json.optString("access_token")
            if (accessToken.isBlank()) return null
            val refreshToken = json.optString("refresh_token").ifBlank { existingRefreshToken.orEmpty() }.takeIf { it.isNotBlank() }
            val expiresIn = json.optLong("expires_in", 3500L)
            val idToken = json.optString("id_token")
            val email = extractEmailFromIdToken(idToken)

            OAuthTokenBundle(
                accessToken = accessToken,
                refreshToken = refreshToken,
                email = email,
                expiresInSeconds = expiresIn
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchEmailFromUserInfo(accessToken: String): String? {
        return try {
            val conn = (URL(GOOGLE_USERINFO_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return null
            JSONObject(body).optString("email").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEmailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) return null
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            payloadJson.optString("email").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (query.isBlank()) return result
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun buildCompletionHtml(success: Boolean): String {
        val title = if (success) "Google Drive Connected" else "Authorization Cancelled"
        val subtitle = if (success) {
            "Encrypz folder sync is active. Returning to the Encrypz app..."
        } else {
            "You can return to the Encrypz app and try again."
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                <title>$title - Encrypz</title>
                <style>
                    body {
                        margin: 0;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        background: #06080F;
                        color: #F8FAFC;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        padding: 24px;
                    }
                    .card {
                        max-width: 380px;
                        width: 100%;
                        background: #0D111C;
                        border: 1px solid #1E293B;
                        border-radius: 18px;
                        padding: 28px 24px;
                        text-align: center;
                    }
                    h2 { margin: 0 0 10px 0; font-size: 20px; }
                    p { margin: 0 0 20px 0; color: #94A3B8; font-size: 14px; line-height: 1.5; }
                    a.btn {
                        display: inline-block;
                        background: #0253A5;
                        color: #FFFFFF;
                        text-decoration: none;
                        padding: 12px 24px;
                        border-radius: 999px;
                        font-weight: 600;
                        font-size: 14px;
                    }
                </style>
                <script>
                    setTimeout(function() {
                        window.location.replace("intent://oauth-complete#Intent;scheme=encrypz;package=com.example.encrypz;end");
                    }, 300);
                </script>
            </head>
            <body>
                <div class="card">
                    <h2>$title</h2>
                    <p>$subtitle</p>
                    <a class="btn" href="intent://oauth-complete#Intent;scheme=encrypz;package=com.example.encrypz;end">Return to Encrypz App</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

