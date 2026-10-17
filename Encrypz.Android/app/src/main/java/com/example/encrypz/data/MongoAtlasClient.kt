package com.example.encrypz.data

import com.example.encrypz.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure Kotlin/Android TLS + SCRAM-SHA-256 + BSON OP_MSG wire-protocol client for MongoDB Atlas.
 * Connection credentials and shard hosts are injected at build time from `local.properties` via [BuildConfig].
 *
 * Avoids JVM-only JNDI (`javax.naming`) and JMX (`java.lang.management`) classes that crash on Android ART.
 */
object MongoAtlasClient {

    val MONGO_URI_TEMPLATE: String = BuildConfig.MONGO_URI_TEMPLATE
    val MONGO_USER: String = BuildConfig.MONGO_USER
    val MONGO_PASS: String = BuildConfig.MONGO_PASS
    const val DEFAULT_DB_NAME = "encrypz"
    const val USERS_COLLECTION = "vault_users"
    const val FILES_COLLECTION = "vault_files"

    private val ATLAS_SHARD_HOSTS: List<String> = BuildConfig.MONGO_SHARD_HOSTS
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    private const val ATLAS_PORT = 27017

    data class MongoVaultUserRecord(
        val email: String,
        val passwordSaltHex: String,
        val passwordVerifierHex: String,
        val wrappedVaultKeyHex: String,
        val pinVerifierHex: String? = null,
        val hasLoggedInOnce: Boolean = true,
        val driveFolderId: String? = null,
        val googleRefreshToken: String? = null,
        val updatedAt: String = ""
    )

    data class BsonBinary(
        val subtype: Byte = 0x00,
        val bytes: ByteArray
    )

    /**
     * Fetches the user's vault master-password record from MongoDB Atlas (`encrypz.vault_users`).
     */
    fun findUserVaultRecord(email: String): MongoVaultUserRecord? {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        if (normalizedEmail.isEmpty()) return null

        return executeOnPrimary { session ->
            val filter = linkedMapOf<String, Any?>(
                "email" to normalizedEmail
            )
            val cmd = linkedMapOf<String, Any?>(
                "find" to USERS_COLLECTION,
                "filter" to filter,
                "limit" to 1,
                "\$db" to DEFAULT_DB_NAME
            )
            val response = session.runCommand(cmd)
            val cursor = response["cursor"] as? Map<*, *> ?: return@executeOnPrimary null
            val firstBatch = cursor["firstBatch"] as? List<*> ?: return@executeOnPrimary null
            val doc = firstBatch.firstOrNull() as? Map<*, *> ?: return@executeOnPrimary null

            val saltHex = doc["passwordSaltHex"] as? String ?: return@executeOnPrimary null
            val verifierHex = doc["passwordVerifierHex"] as? String ?: return@executeOnPrimary null
            val wrappedKeyHex = doc["wrappedVaultKeyHex"] as? String ?: return@executeOnPrimary null

            MongoVaultUserRecord(
                email = normalizedEmail,
                passwordSaltHex = saltHex,
                passwordVerifierHex = verifierHex,
                wrappedVaultKeyHex = wrappedKeyHex,
                pinVerifierHex = (doc["pinVerifierHex"] as? String)?.takeIf { it.isNotBlank() },
                hasLoggedInOnce = (doc["hasLoggedInOnce"] as? Boolean) ?: true,
                driveFolderId = (doc["driveFolderId"] as? String)?.takeIf { it.isNotBlank() },
                googleRefreshToken = (doc["googleRefreshToken"] as? String)?.takeIf { it.isNotBlank() },
                updatedAt = doc["updatedAt"] as? String ?: ""
            )
        }
    }

    /**
     * Creates or updates the user's master-password verifier & wrapped AES-256 vault key in MongoDB Atlas.
     */
    fun upsertUserVaultRecord(record: MongoVaultUserRecord): Boolean {
        val normalizedEmail = record.email.trim().lowercase(Locale.ROOT)
        if (normalizedEmail.isEmpty()) return false

        return executeOnPrimary { session ->
            val setFields = linkedMapOf<String, Any?>(
                "email" to normalizedEmail,
                "passwordSaltHex" to record.passwordSaltHex,
                "passwordVerifierHex" to record.passwordVerifierHex,
                "wrappedVaultKeyHex" to record.wrappedVaultKeyHex,
                "pinVerifierHex" to (record.pinVerifierHex ?: ""),
                "hasLoggedInOnce" to record.hasLoggedInOnce,
                "updatedAt" to Date().toString()
            )
            if (!record.driveFolderId.isNullOrBlank()) {
                setFields["driveFolderId"] = record.driveFolderId
            }
            if (!record.googleRefreshToken.isNullOrBlank()) {
                setFields["googleRefreshToken"] = record.googleRefreshToken
            }

            val updateSpec = linkedMapOf<String, Any?>(
                "q" to linkedMapOf<String, Any?>("email" to normalizedEmail),
                "u" to linkedMapOf<String, Any?>("\$set" to setFields),
                "upsert" to true
            )
            val cmd = linkedMapOf<String, Any?>(
                "update" to USERS_COLLECTION,
                "updates" to listOf(updateSpec),
                "\$db" to DEFAULT_DB_NAME
            )
            val res = session.runCommand(cmd)
            val ok = (res["ok"] as? Number)?.toInt() ?: 0
            ok == 1
        } ?: false
    }

    /**
     * Records an encrypted (.enc) file's metadata and Google Drive file ID in MongoDB Atlas (`encrypz.vault_files`).
     */
    fun upsertEncryptedFileMetadata(
        fileId: String,
        email: String,
        encryptedFileName: String,
        driveFileId: String?,
        driveFolderName: String,
        ivHex: String,
        sizeBytes: Long,
        uploadedAt: String,
        isDeleted: Boolean
    ): Boolean {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        return executeOnPrimary { session ->
            val setFields = linkedMapOf<String, Any?>(
                "fileId" to fileId,
                "email" to normalizedEmail,
                "encryptedFileName" to encryptedFileName,
                "driveFileId" to (driveFileId ?: ""),
                "driveFolderName" to driveFolderName,
                "ivHex" to ivHex,
                "sizeBytes" to sizeBytes,
                "uploadedAt" to uploadedAt,
                "isDeleted" to isDeleted,
                "updatedAt" to Date().toString()
            )
            val updateSpec = linkedMapOf<String, Any?>(
                "q" to linkedMapOf<String, Any?>("fileId" to fileId),
                "u" to linkedMapOf<String, Any?>("\$set" to setFields),
                "upsert" to true
            )
            val cmd = linkedMapOf<String, Any?>(
                "update" to FILES_COLLECTION,
                "updates" to listOf(updateSpec),
                "\$db" to DEFAULT_DB_NAME
            )
            val res = session.runCommand(cmd)
            ((res["ok"] as? Number)?.toInt() ?: 0) == 1
        } ?: false
    }

    internal fun <T> executeOnPrimary(block: (MongoSession) -> T): T? {
        var discoveredPrimaryHost: String? = null

        for (candidateHost in ATLAS_SHARD_HOSTS) {
            try {
                openSession(candidateHost, ATLAS_PORT).use { session ->
                    val hello = session.runCommand(
                        linkedMapOf(
                            "hello" to 1,
                            "\$db" to "admin"
                        )
                    )
                    val isWritablePrimary = (hello["isWritablePrimary"] as? Boolean)
                        ?: (hello["ismaster"] as? Boolean)
                        ?: false
                    if (isWritablePrimary) {
                        session.authenticateScramSha256(MONGO_USER, MONGO_PASS)
                        return block(session)
                    } else {
                        val primary = hello["primary"] as? String
                        if (!primary.isNullOrBlank()) {
                            discoveredPrimaryHost = primary.substringBefore(':')
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next shard
            }
        }

        val targetPrimary = discoveredPrimaryHost ?: return null
        return try {
            openSession(targetPrimary, ATLAS_PORT).use { session ->
                session.authenticateScramSha256(MONGO_USER, MONGO_PASS)
                block(session)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openSession(host: String, port: Int): MongoSession {
        val tcpSocket = java.net.Socket()
        tcpSocket.soTimeout = 12_000
        tcpSocket.connect(InetSocketAddress(host, port), 10_000)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val rawSocket = factory.createSocket(tcpSocket, host, port, true) as SSLSocket
        rawSocket.soTimeout = 12_000
        rawSocket.startHandshake()
        return MongoSession(rawSocket)
    }

    internal class MongoSession(private val socket: SSLSocket) : AutoCloseable {
        private val input = DataInputStream(socket.inputStream)
        private val output = socket.outputStream
        private var nextRequestId = 1

        fun runCommand(commandDoc: Map<String, Any?>): Map<String, Any?> {
            val bsonBytes = encodeBsonDocument(commandDoc)
            val messageLength = 16 + 4 + 1 + bsonBytes.size
            val requestId = nextRequestId++

            val packet = ByteBuffer.allocate(messageLength).order(ByteOrder.LITTLE_ENDIAN)
            packet.putInt(messageLength)
            packet.putInt(requestId)
            packet.putInt(0) // responseTo
            packet.putInt(2013) // OP_MSG
            packet.putInt(0) // flagBits
            packet.put(0.toByte()) // section kind 0 (body)
            packet.put(bsonBytes)

            output.write(packet.array())
            output.flush()

            val headerBytes = ByteArray(16)
            input.readFully(headerBytes)
            val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val respLen = headerBuf.int
            headerBuf.int // respId
            headerBuf.int // responseTo
            val opCode = headerBuf.int

            if (respLen < 21 || respLen > 16 * 1024 * 1024) {
                throw IOException("Invalid MongoDB response length: $respLen")
            }
            val bodyBytes = ByteArray(respLen - 16)
            input.readFully(bodyBytes)

            if (opCode != 2013) {
                throw IOException("Unexpected MongoDB opcode: $opCode")
            }
            // Skip 4-byte flagBits and 1-byte sectionKind
            val bsonOffset = 5
            return decodeBsonDocument(bodyBytes, bsonOffset).first
        }

        fun authenticateScramSha256(username: String, password: String) {
            val nonceBytes = ByteArray(24)
            SecureRandom().nextBytes(nonceBytes)
            val clientNonce = base64Encode(nonceBytes)

            val safeUser = username.replace("=", "=3D").replace(",", "=2C")
            val clientFirstBare = "n=$safeUser,r=$clientNonce"
            val clientFirstMsg = "n,,$clientFirstBare"

            val startCmd = linkedMapOf<String, Any?>(
                "saslStart" to 1,
                "mechanism" to "SCRAM-SHA-256",
                "payload" to BsonBinary(0x00, clientFirstMsg.toByteArray(Charsets.UTF_8)),
                "autoAuthorize" to 1,
                "\$db" to "admin"
            )
            val startResp = runCommand(startCmd)
            if (((startResp["ok"] as? Number)?.toInt() ?: 0) != 1) {
                throw IOException("MongoDB saslStart failed: ${startResp["errmsg"]}")
            }

            val conversationId = (startResp["conversationId"] as? Number)?.toInt()
                ?: throw IOException("Missing conversationId")
            val serverFirstBinary = startResp["payload"] as? BsonBinary
                ?: throw IOException("Missing saslStart payload")
            val serverFirstMsg = String(serverFirstBinary.bytes, Charsets.UTF_8)

            val parsedServerFirst = parseScramAttributes(serverFirstMsg)
            val combinedNonce = parsedServerFirst["r"] ?: throw IOException("Missing SCRAM r")
            val saltB64 = parsedServerFirst["s"] ?: throw IOException("Missing SCRAM s")
            val iterations = parsedServerFirst["i"]?.toIntOrNull() ?: throw IOException("Missing SCRAM i")

            if (!combinedNonce.startsWith(clientNonce)) {
                throw IOException("SCRAM server nonce mismatch")
            }

            val saltBytes = base64Decode(saltB64)
            val saltedPassword = pbkdf2HmacSha256(password, saltBytes, iterations)
            val clientKey = hmacSha256(saltedPassword, "Client Key".toByteArray(Charsets.UTF_8))
            val storedKey = sha256(clientKey)

            val clientFinalWithoutProof = "c=biws,r=$combinedNonce"
            val authMessage = "$clientFirstBare,$serverFirstMsg,$clientFinalWithoutProof"
                .toByteArray(Charsets.UTF_8)
            val clientSignature = hmacSha256(storedKey, authMessage)
            val clientProof = ByteArray(clientKey.size) { i ->
                (clientKey[i].toInt() xor clientSignature[i].toInt()).toByte()
            }
            val clientFinalMsg = "$clientFinalWithoutProof,p=${base64Encode(clientProof)}"

            val continueCmd = linkedMapOf<String, Any?>(
                "saslContinue" to 1,
                "conversationId" to conversationId,
                "payload" to BsonBinary(0x00, clientFinalMsg.toByteArray(Charsets.UTF_8)),
                "\$db" to "admin"
            )
            var contResp = runCommand(continueCmd)
            if (((contResp["ok"] as? Number)?.toInt() ?: 0) != 1) {
                throw IOException("MongoDB saslContinue failed: ${contResp["errmsg"]}")
            }

            val isDone = contResp["done"] as? Boolean ?: false
            if (!isDone) {
                val finalStepCmd = linkedMapOf<String, Any?>(
                    "saslContinue" to 1,
                    "conversationId" to conversationId,
                    "payload" to BsonBinary(0x00, ByteArray(0)),
                    "\$db" to "admin"
                )
                contResp = runCommand(finalStepCmd)
                if (((contResp["ok"] as? Number)?.toInt() ?: 0) != 1) {
                    throw IOException("MongoDB final saslContinue failed: ${contResp["errmsg"]}")
                }
            }
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseScramAttributes(msg: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in msg.split(",")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                map[part.substring(0, idx)] = part.substring(idx + 1)
            }
        }
        return map
    }

    private fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        }
    }

    internal fun encodeBsonDocument(map: Map<String, Any?>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((key, value) in map) {
            writeBsonElement(body, key, value)
        }
        val bodyBytes = body.toByteArray()
        val totalLen = 4 + bodyBytes.size + 1
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLen)
        buf.put(bodyBytes)
        buf.put(0.toByte())
        return buf.array()
    }

    private fun writeBsonElement(out: ByteArrayOutputStream, name: String, value: Any?) {
        when (value) {
            null -> {
                out.write(0x0A)
                writeCString(out, name)
            }
            is String -> {
                out.write(0x02)
                writeCString(out, name)
                val utf8 = value.toByteArray(Charsets.UTF_8)
                writeInt32LE(out, utf8.size + 1)
                out.write(utf8)
                out.write(0)
            }
            is Int -> {
                out.write(0x10)
                writeCString(out, name)
                writeInt32LE(out, value)
            }
            is Long -> {
                out.write(0x12)
                writeCString(out, name)
                writeInt64LE(out, value)
            }
            is Double -> {
                out.write(0x01)
                writeCString(out, name)
                writeInt64LE(out, java.lang.Double.doubleToRawLongBits(value))
            }
            is Boolean -> {
                out.write(0x08)
                writeCString(out, name)
                out.write(if (value) 1 else 0)
            }
            is BsonBinary -> {
                out.write(0x05)
                writeCString(out, name)
                writeInt32LE(out, value.bytes.size)
                out.write(value.subtype.toInt())
                out.write(value.bytes)
            }
            is Map<*, *> -> {
                out.write(0x03)
                writeCString(out, name)
                @Suppress("UNCHECKED_CAST")
                out.write(encodeBsonDocument(value as Map<String, Any?>))
            }
            is List<*> -> {
                out.write(0x04)
                writeCString(out, name)
                val indexedMap = linkedMapOf<String, Any?>()
                value.forEachIndexed { idx, item ->
                    indexedMap[idx.toString()] = item
                }
                out.write(encodeBsonDocument(indexedMap))
            }
        }
    }

    private fun writeCString(out: ByteArrayOutputStream, str: String) {
        out.write(str.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeInt64LE(out: ByteArrayOutputStream, v: Long) {
        for (shift in 0 until 64 step 8) {
            out.write(((v ushr shift) and 0xFFL).toInt())
        }
    }

    internal fun decodeBsonDocument(bytes: ByteArray, startOffset: Int): Pair<Map<String, Any?>, Int> {
        val docLen = readInt32LE(bytes, startOffset)
        val endOffset = startOffset + docLen
        var pos = startOffset + 4
        val result = linkedMapOf<String, Any?>()

        while (pos < endOffset - 1) {
            val type = bytes[pos].toInt() and 0xFF
            pos += 1
            if (type == 0) break

            val nameEnd = indexOfZero(bytes, pos)
            val name = String(bytes, pos, nameEnd - pos, Charsets.UTF_8)
            pos = nameEnd + 1

            when (type) {
                0x01 -> { // Double
                    val bits = readInt64LE(bytes, pos)
                    result[name] = java.lang.Double.longBitsToDouble(bits)
                    pos += 8
                }
                0x02 -> { // UTF-8 String
                    val strLen = readInt32LE(bytes, pos)
                    pos += 4
                    val str = if (strLen > 1) {
                        String(bytes, pos, strLen - 1, Charsets.UTF_8)
                    } else {
                        ""
                    }
                    result[name] = str
                    pos += strLen
                }
                0x03 -> { // Embedded Document
                    val (subDoc, nextPos) = decodeBsonDocument(bytes, pos)
                    result[name] = subDoc
                    pos = nextPos
                }
                0x04 -> { // Array
                    val (subDoc, nextPos) = decodeBsonDocument(bytes, pos)
                    result[name] = subDoc.values.toList()
                    pos = nextPos
                }
                0x05 -> { // Binary
                    val binLen = readInt32LE(bytes, pos)
                    pos += 4
                    val subtype = bytes[pos]
                    pos += 1
                    val binBytes = bytes.copyOfRange(pos, pos + binLen)
                    result[name] = BsonBinary(subtype, binBytes)
                    pos += binLen
                }
                0x07 -> { // ObjectId (12 bytes)
                    val oidBytes = bytes.copyOfRange(pos, pos + 12)
                    result[name] = oidBytes.joinToString("") { "%02x".format(it) }
                    pos += 12
                }
                0x08 -> { // Boolean
                    result[name] = bytes[pos].toInt() != 0
                    pos += 1
                }
                0x09, 0x11, 0x12 -> { // UTC DateTime, Timestamp, Int64
                    result[name] = readInt64LE(bytes, pos)
                    pos += 8
                }
                0x0A, 0xFF, 0x7F -> { // Null / MinKey / MaxKey
                    result[name] = null
                }
                0x10 -> { // Int32
                    result[name] = readInt32LE(bytes, pos)
                    pos += 4
                }
                0x13 -> { // Decimal128
                    pos += 16
                }
                else -> {
                    // Stop parsing unknown element types safely
                    break
                }
            }
        }
        return result to endOffset
    }

    private fun indexOfZero(bytes: ByteArray, start: Int): Int {
        var i = start
        while (i < bytes.size && bytes[i].toInt() != 0) {
            i++
        }
        return i
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readInt64LE(bytes: ByteArray, offset: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = res or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return res
    }
}

