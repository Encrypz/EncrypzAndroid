package com.encrypz.privacy.crypto.vault

import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin RFC 3394 AES Key Wrap / Key Unwrap over `AES/ECB/NoPadding`.
 */
object AesKeyWrap {
    private val DEFAULT_IV = ByteArray(8) { 0xA6.toByte() }

    fun wrap(kek: ByteArray, keyData: ByteArray): ByteArray {
        require(kek.size == 16 || kek.size == 24 || kek.size == 32) { "Invalid KEK length: ${kek.size}" }
        require(keyData.size >= 16 && keyData.size % 8 == 0) { "Key data must be a multiple of 8 bytes (>= 16)" }

        val n = keyData.size / 8
        val r = ByteArray(keyData.size)
        System.arraycopy(keyData, 0, r, 0, keyData.size)
        val a = DEFAULT_IV.copyOf()

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"))
        val block = ByteArray(16)

        for (j in 0..5) {
            for (i in 1..n) {
                val rOffset = (i - 1) * 8
                System.arraycopy(a, 0, block, 0, 8)
                System.arraycopy(r, rOffset, block, 8, 8)
                val b = cipher.doFinal(block)
                val t = (n * j + i).toLong()
                System.arraycopy(b, 0, a, 0, 8)
                for (k in 0..7) {
                    val shift = (7 - k) * 8
                    a[k] = (a[k].toInt() xor ((t ushr shift) and 0xFF).toInt()).toByte()
                }
                System.arraycopy(b, 8, r, rOffset, 8)
            }
        }

        val out = ByteArray(8 + keyData.size)
        System.arraycopy(a, 0, out, 0, 8)
        System.arraycopy(r, 0, out, 8, r.size)
        block.fill(0)
        a.fill(0)
        r.fill(0)
        return out
    }

    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
        require(kek.size == 16 || kek.size == 24 || kek.size == 32) { "Invalid KEK length: ${kek.size}" }
        require(wrapped.size >= 24 && wrapped.size % 8 == 0) { "Wrapped key must be a multiple of 8 bytes (>= 24)" }

        val n = (wrapped.size / 8) - 1
        val a = wrapped.copyOfRange(0, 8)
        val r = wrapped.copyOfRange(8, wrapped.size)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"))
        val block = ByteArray(16)

        try {
            for (j in 5 downTo 0) {
                for (i in n downTo 1) {
                    val rOffset = (i - 1) * 8
                    val t = (n * j + i).toLong()
                    System.arraycopy(a, 0, block, 0, 8)
                    for (k in 0..7) {
                        val shift = (7 - k) * 8
                        block[k] = (block[k].toInt() xor ((t ushr shift) and 0xFF).toInt()).toByte()
                    }
                    System.arraycopy(r, rOffset, block, 8, 8)
                    val b = cipher.doFinal(block)
                    System.arraycopy(b, 0, a, 0, 8)
                    System.arraycopy(b, 8, r, rOffset, 8)
                }
            }
            if (!MessageDigest.isEqual(a, DEFAULT_IV)) {
                r.fill(0)
                throw AEADBadTagException("Key wrap integrity check failed")
            }
            return r
        } finally {
            block.fill(0)
            a.fill(0)
        }
    }
}
