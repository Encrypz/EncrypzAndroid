package com.encrypz.privacy.crypto.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypz Vault v2 (`EPZ2`) authenticated chunked file encryption:
 * - 68-byte binary header:
 *   `magic "EPZ2" (4 B) || formatVersion=2 (2 B BE) || chunkShift=16 (2 B BE) || headerNonce (12 B) || AES-256-GCM(encKey, headerNonce, aad=header[0..7], contentKey (32 B))`
 * - 64 KiB chunks:
 *   `chunkNonce (12 B) || AES-256-GCM(contentKey, chunkNonce, aad="EPZ2" || uint64BE(chunkIndex) || headerNonce, chunk)`
 */
object VaultFileCrypto {
    private val MAGIC_BYTES = byteArrayOf(
        'E'.code.toByte(),
        'P'.code.toByte(),
        'Z'.code.toByte(),
        '2'.code.toByte()
    )
    private const val FORMAT_VERSION: Short = 2
    private const val CHUNK_SHIFT: Short = 16 // 1 << 16 = 65536 bytes (64 KiB)
    private const val PREFIX_SIZE = 8
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val CONTENT_KEY_SIZE = 32
    const val HEADER_SIZE = PREFIX_SIZE + NONCE_SIZE + CONTENT_KEY_SIZE + TAG_BYTES // 68
    const val CLEARTEXT_CHUNK_SIZE = 64 * 1024 // 65536
    const val CIPHERTEXT_CHUNK_SIZE = NONCE_SIZE + CLEARTEXT_CHUNK_SIZE + TAG_BYTES // 65564

    fun encryptBytes(plaintext: ByteArray, masterkey: VaultMasterkey.MasterkeySet): ByteArray {
        val encKey = masterkey.copyEncKey()
        return try {
            encryptBytes(plaintext, encKey)
        } finally {
            encKey.fill(0)
        }
    }

    fun decryptBytes(encrypted: ByteArray, masterkey: VaultMasterkey.MasterkeySet): ByteArray {
        val encKey = masterkey.copyEncKey()
        return try {
            decryptBytes(encrypted, encKey)
        } finally {
            encKey.fill(0)
        }
    }

    fun encryptBytes(plaintext: ByteArray, encKey: ByteArray): ByteArray {
        val estimatedChunks = maxOf(1, (plaintext.size + CLEARTEXT_CHUNK_SIZE - 1) / CLEARTEXT_CHUNK_SIZE)
        val out = ByteArrayOutputStream(HEADER_SIZE + plaintext.size + estimatedChunks * (NONCE_SIZE + TAG_BYTES))
        encryptStream(ByteArrayInputStream(plaintext), out, encKey)
        return out.toByteArray()
    }

    fun decryptBytes(encrypted: ByteArray, encKey: ByteArray): ByteArray {
        if (encrypted.size < HEADER_SIZE) {
            throw IOException("Truncated Encrypz file header")
        }
        val out = ByteArrayOutputStream(maxOf(0, encrypted.size - HEADER_SIZE))
        decryptStream(ByteArrayInputStream(encrypted), out, encKey)
        return out.toByteArray()
    }

    fun encryptStream(input: InputStream, output: OutputStream, encKey: ByteArray) {
        require(encKey.size == 32) { "Encrypz encKey must be 32 bytes" }
        val random = SecureRandom()
        val headerPrefix = ByteBuffer.allocate(PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_BYTES)
            putShort(FORMAT_VERSION)
            putShort(CHUNK_SHIFT)
        }.array()
        val headerNonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val contentKeyBytes = ByteArray(CONTENT_KEY_SIZE).also { random.nextBytes(it) }

        val contentKeySpec = SecretKeySpec(contentKeyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val chunkNonce = ByteArray(NONCE_SIZE)
        val aadBuf = ByteBuffer.allocate(MAGIC_BYTES.size + 8 + NONCE_SIZE).order(ByteOrder.BIG_ENDIAN)
        val readBuf = ByteArray(CLEARTEXT_CHUNK_SIZE)

        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, headerNonce))
            cipher.updateAAD(headerPrefix)
            val encryptedHeader = cipher.doFinal(contentKeyBytes)
            output.write(headerPrefix)
            output.write(headerNonce)
            output.write(encryptedHeader)

            var chunkIndex = 0L
            while (true) {
                val read = readUpTo(input, readBuf)
                if (read == 0) {
                    // Empty 0-byte files consist solely of the authenticated 68-byte EPZ2 header.
                    break
                }
                random.nextBytes(chunkNonce)
                aadBuf.clear()
                aadBuf.put(MAGIC_BYTES)
                aadBuf.putLong(chunkIndex)
                aadBuf.put(headerNonce)
                cipher.init(Cipher.ENCRYPT_MODE, contentKeySpec, GCMParameterSpec(TAG_BITS, chunkNonce))
                cipher.updateAAD(aadBuf.array())
                val encChunk = cipher.doFinal(readBuf, 0, read)
                output.write(chunkNonce)
                output.write(encChunk)
                chunkIndex++
            }
        } finally {
            contentKeyBytes.fill(0)
            readBuf.fill(0)
        }
    }

    fun decryptStream(input: InputStream, output: OutputStream, encKey: ByteArray) {
        require(encKey.size == 32) { "Encrypz encKey must be 32 bytes" }
        val headerBytes = ByteArray(HEADER_SIZE)
        if (!readExact(input, headerBytes)) {
            throw IOException("Truncated Encrypz file header")
        }
        val headerPrefix = headerBytes.copyOfRange(0, PREFIX_SIZE)
        for (i in MAGIC_BYTES.indices) {
            if (headerPrefix[i] != MAGIC_BYTES[i]) {
                throw AEADBadTagException("Invalid EPZ2 header magic bytes")
            }
        }
        val prefixView = ByteBuffer.wrap(headerPrefix).order(ByteOrder.BIG_ENDIAN)
        prefixView.position(MAGIC_BYTES.size)
        val formatVersion = prefixView.short
        val chunkShift = prefixView.short
        if (formatVersion != FORMAT_VERSION || chunkShift != CHUNK_SHIFT) {
            throw AEADBadTagException("Unsupported EPZ2 header parameters")
        }

        val headerNonce = headerBytes.copyOfRange(PREFIX_SIZE, PREFIX_SIZE + NONCE_SIZE)
        val encryptedHeader = headerBytes.copyOfRange(PREFIX_SIZE + NONCE_SIZE, HEADER_SIZE)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, headerNonce))
        cipher.updateAAD(headerPrefix)
        val contentKeyBytes = cipher.doFinal(encryptedHeader)

        try {
            if (contentKeyBytes.size != CONTENT_KEY_SIZE) {
                throw AEADBadTagException("Invalid EPZ2 content key length")
            }
            val contentKeySpec = SecretKeySpec(contentKeyBytes, "AES")

            val aadBuf = ByteBuffer.allocate(MAGIC_BYTES.size + 8 + NONCE_SIZE).order(ByteOrder.BIG_ENDIAN)
            val chunkBuf = ByteArray(CIPHERTEXT_CHUNK_SIZE)
            var chunkIndex = 0L

            while (true) {
                val read = readUpTo(input, chunkBuf)
                if (read == 0) break
                if (read < NONCE_SIZE + TAG_BYTES) {
                    throw AEADBadTagException("Truncated EPZ2 file chunk #$chunkIndex")
                }
                aadBuf.clear()
                aadBuf.put(MAGIC_BYTES)
                aadBuf.putLong(chunkIndex)
                aadBuf.put(headerNonce)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    contentKeySpec,
                    GCMParameterSpec(TAG_BITS, chunkBuf, 0, NONCE_SIZE)
                )
                cipher.updateAAD(aadBuf.array())
                val plainChunk = cipher.doFinal(chunkBuf, NONCE_SIZE, read - NONCE_SIZE)
                output.write(plainChunk)
                chunkIndex++
            }
        } finally {
            contentKeyBytes.fill(0)
        }
    }

    private fun readExact(input: InputStream, target: ByteArray): Boolean {
        var total = 0
        while (total < target.size) {
            val n = input.read(target, total, target.size - total)
            if (n < 0) return false
            total += n
        }
        return true
    }

    private fun readUpTo(input: InputStream, target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val n = input.read(target, total, target.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
