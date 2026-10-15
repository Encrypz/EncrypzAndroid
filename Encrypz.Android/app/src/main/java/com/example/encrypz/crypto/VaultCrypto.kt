package com.example.encrypz.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_LENGTH = 32

    /**
     * Derives a wrapping key using PBKDF2-HMAC-SHA-256 as specified in the Encrypz README.
     */
    fun deriveWrappingKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Generates a random AES-256 vault key.
     */
    fun generateVaultKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        return keyGen.generateKey()
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
}
