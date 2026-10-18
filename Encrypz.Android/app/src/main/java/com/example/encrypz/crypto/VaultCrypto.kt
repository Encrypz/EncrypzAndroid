package com.example.encrypz.crypto

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class PasswordStrengthLevel(val label: String, val fraction: Float) {
    WEAK("Weak", 0.25f),
    FAIR("Fair", 0.50f),
    GOOD("Strong", 0.75f),
    EXCELLENT("Zero-Compromise", 1.0f)
}

data class PasswordStrength(
    val level: PasswordStrengthLevel,
    val hint: String
)

object VaultCrypto {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 600_000
    const val SALT_LENGTH = 32

    /**
     * Generates a cryptographically random 32-byte salt.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun getPbkdf2Factory(): SecretKeyFactory {
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
    }

    /**
     * Derives a wrapping key using PBKDF2-HMAC-SHA-256 as specified in the Encrypz README.
     */
    fun deriveWrappingKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try { SecretKeySpec(factory.generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    /**
     * Fast key derivation for responsive interactive UI previews when unlocking demo/session vaults.
     */
    fun deriveSessionKeyFast(password: String, salt: ByteArray): SecretKey {
        val factory = getPbkdf2Factory()
        val spec = PBEKeySpec(password.toCharArray(), salt, 10_000, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Computes a deterministic, zero-knowledge PBKDF2-HMAC-SHA256 + SHA-256 password verifier hex string
     * for storing & verifying the user's Master Password in MongoDB Atlas and LocalVaultStorage.
     */
    fun computePasswordVerifierHex(password: String, salt: ByteArray): String {
        val wrappingKeyBytes = deriveSessionKeyFast(password, salt).encoded
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("ENCRYPZ_MASTER_PASSWORD_VERIFIER_V1:".encodeToByteArray())
        digest.update(salt)
        val hashBytes = digest.digest(wrappingKeyBytes)
        return toHex(hashBytes)
    }

    /**
     * Computes a deterministic PBKDF2-HMAC-SHA256 + SHA-256 verifier hex string for the user's
     * Quick-Unlock PIN configured after first login.
     */
    fun computePinVerifierHex(pin: String, salt: ByteArray): String {
        val pinKeyBytes = deriveSessionKeyFast("PIN:$pin", salt).encoded
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("ENCRYPZ_PIN_VERIFIER_V1:".encodeToByteArray())
        digest.update(salt)
        val hashBytes = digest.digest(pinKeyBytes)
        return toHex(hashBytes)
    }

    fun accountProof(wrappingKey: SecretKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("ENCRYPZ_ACCOUNT_PROOF_V2:".encodeToByteArray())
        return toHex(digest.digest(wrappingKey.encoded))
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Generates a random 256-bit AES vault key backed directly by a 32-byte SecretKeySpec
     * so .encoded is guaranteed non-null across all Android versions and OEM providers.
     */
    fun generateVaultKey(): SecretKey {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts the generated vault key using the derived password wrapping key.
     */
    fun encryptVaultKey(vaultKey: SecretKey, wrappingKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, parameterSpec)
        val cipherText = cipher.doFinal(vaultKey.encoded)

        // Prepend IV to ciphertext
        return iv + cipherText
    }

    /**
     * Decrypts the vault key using the derived password wrapping key.
     */
    fun decryptVaultKey(encryptedVaultKeyWithIv: ByteArray, wrappingKey: SecretKey): SecretKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encryptedVaultKeyWithIv.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = encryptedVaultKeyWithIv.copyOfRange(GCM_IV_LENGTH, encryptedVaultKeyWithIv.size)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, parameterSpec)
        val decoded = cipher.doFinal(cipherText)
        return SecretKeySpec(decoded, "AES")
    }

    /**
     * Encrypts file payload with the Vault Key.
     */
    fun encryptPayload(payload: ByteArray, vaultKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, vaultKey, parameterSpec)
        val cipherText = cipher.doFinal(payload)

        return iv + cipherText
    }

    /**
     * Decrypts file payload with the Vault Key.
     */
    fun decryptPayload(encryptedPayloadWithIv: ByteArray, vaultKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encryptedPayloadWithIv.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = encryptedPayloadWithIv.copyOfRange(GCM_IV_LENGTH, encryptedPayloadWithIv.size)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, vaultKey, parameterSpec)
        return cipher.doFinal(cipherText)
    }

    /**
     * Extracts hex representation of the 12-byte GCM IV from an encrypted payload.
     */
    fun extractIvHex(encryptedPayloadWithIv: ByteArray): String {
        if (encryptedPayloadWithIv.size < GCM_IV_LENGTH) return "N/A"
        return encryptedPayloadWithIv.take(GCM_IV_LENGTH).joinToString("") { "%02x".format(it) }
    }

    /**
     * Evaluates master password strength for user-friendly feedback.
     */
    fun evaluatePasswordStrength(password: String): PasswordStrength {
        if (password.isEmpty()) {
            return PasswordStrength(PasswordStrengthLevel.WEAK, "Enter at least 12 characters")
        }
        var score = 0
        if (password.length >= 8) score++
        if (password.length >= 12) score++
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        val variety = listOf(hasUpper, hasLower, hasDigit, hasSymbol).count { it }
        if (variety >= 2) score++
        if (variety >= 3 && password.length >= 14) score++

        return when {
            score <= 1 -> PasswordStrength(
                PasswordStrengthLevel.WEAK,
                "Add more characters (12+ recommended by Encrypz)"
            )
            score == 2 -> PasswordStrength(
                PasswordStrengthLevel.FAIR,
                "Good start — mix upper/lowercase, numbers & symbols"
            )
            score == 3 -> PasswordStrength(
                PasswordStrengthLevel.GOOD,
                "Strong passphrase suitable for AES-256-GCM key derivation"
            )
            else -> PasswordStrength(
                PasswordStrengthLevel.EXCELLENT,
                "High-entropy zero-knowledge master passphrase"
            )
        }
    }
}
