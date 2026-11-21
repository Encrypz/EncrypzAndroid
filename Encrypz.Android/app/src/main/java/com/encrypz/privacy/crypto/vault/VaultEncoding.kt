package com.encrypz.privacy.crypto.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Pure-Kotlin RFC 4648 encodings (Base64, Base64URL, Base32) and strict flat JSON helpers
 * that run identically on Android API 24+ and local JVM unit tests without `android.*` stubs.
 */
object VaultEncoding {
    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BASE64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val BASE64_REV = IntArray(128) { -1 }.apply {
        BASE64_CHARS.forEachIndexed { idx, c -> this[c.code] = idx }
    }
    private val BASE64URL_REV = IntArray(128) { -1 }.apply {
        BASE64URL_CHARS.forEachIndexed { idx, c -> this[c.code] = idx }
    }
    private val BASE32_REV = IntArray(128) { -1 }.apply {
        BASE32_CHARS.forEachIndexed { idx, c ->
            this[c.code] = idx
            this[c.lowercaseChar().code] = idx
        }
    }

    fun base64Encode(data: ByteArray, pad: Boolean = true): String =
        encodeBase64Family(data, BASE64_CHARS, pad)

    fun base64Decode(input: String): ByteArray =
        decodeBase64Family(input, BASE64_REV)

    fun base64UrlEncode(data: ByteArray, pad: Boolean = true): String =
        encodeBase64Family(data, BASE64URL_CHARS, pad)

    fun base64UrlDecode(input: String): ByteArray =
        decodeBase64Family(input, BASE64URL_REV)

    private fun encodeBase64Family(data: ByteArray, alphabet: String, pad: Boolean): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val hasB1 = i + 1 < data.size
            val hasB2 = i + 2 < data.size
            val b1 = if (hasB1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) data[i + 2].toInt() and 0xFF else 0

            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (hasB1) {
                sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            } else if (pad) {
                sb.append('=')
            }
            if (hasB2) {
                sb.append(alphabet[b2 and 0x3F])
            } else if (pad) {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }

    private fun decodeBase64Family(input: String, rev: IntArray): ByteArray {
        val clean = input.trim()
        if (clean.isEmpty()) return ByteArray(0)
        var end = clean.length
        while (end > 0 && clean[end - 1] == '=') {
            end--
        }
        val rem = end % 4
        require(rem != 1) { "Invalid Base64 input length" }
        val outLen = (end / 4) * 3 + when (rem) {
            2 -> 1
            3 -> 2
            else -> 0
        }
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i + 3 < end) {
            val c0 = decodeBase64Char(clean[i], rev)
            val c1 = decodeBase64Char(clean[i + 1], rev)
            val c2 = decodeBase64Char(clean[i + 2], rev)
            val c3 = decodeBase64Char(clean[i + 3], rev)
            out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            out[outIdx++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
            out[outIdx++] = (((c2 and 0x03) shl 6) or c3).toByte()
            i += 4
        }
        if (rem == 2) {
            val c0 = decodeBase64Char(clean[i], rev)
            val c1 = decodeBase64Char(clean[i + 1], rev)
            out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
        } else if (rem == 3) {
            val c0 = decodeBase64Char(clean[i], rev)
            val c1 = decodeBase64Char(clean[i + 1], rev)
            val c2 = decodeBase64Char(clean[i + 2], rev)
            out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            out[outIdx++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
        }
        return out
    }

    private fun decodeBase64Char(ch: Char, rev: IntArray): Int {
        val code = ch.code
        require(code in 0..127 && rev[code] >= 0) { "Invalid Base64 character: '$ch'" }
        return rev[code]
    }

    fun base32Encode(data: ByteArray, pad: Boolean = false): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size * 8 + 4) / 5))
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_CHARS[(buffer ushr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_CHARS[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        if (pad) {
            while (sb.length % 8 != 0) sb.append('=')
        }
        return sb.toString()
    }

    fun base32Decode(input: String): ByteArray {
        val clean = input.trim().trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        val out = ByteArray((clean.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        var outIdx = 0
        for (ch in clean) {
            val code = ch.code
            val value = if (code in 0..127) BASE32_REV[code] else -1
            require(value >= 0) { "Invalid Base32 character: '$ch'" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[outIdx++] = ((buffer ushr bitsLeft) and 0xFF).toByte()
            }
        }
        return out
    }

    fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 bytes", e)
        }
    }

    fun encodeUtf8Chars(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    fun buildFlatJsonObject(entries: List<Pair<String, Any>>): String {
        val sb = StringBuilder("{")
        entries.forEachIndexed { idx, (key, value) ->
            if (idx > 0) sb.append(',')
            appendJsonString(sb, key)
            sb.append(':')
            when (value) {
                is String -> appendJsonString(sb, value)
                is Number, is Boolean -> sb.append(value.toString())
                else -> throw IllegalArgumentException("Unsupported JSON value type: ${value::class.java}")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    fun parseFlatJsonObject(json: String): Map<String, Any> {
        val parser = FlatJsonParser(json)
        return parser.parseObject()
    }

    private class FlatJsonParser(private val src: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any> {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val map = linkedMapOf<String, Any>()
            if (peek() == '}') {
                pos++
                skipWhitespace()
                require(pos == src.length) { "Trailing characters after JSON object" }
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parsePrimitive()
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at position $pos")
                }
            }
            skipWhitespace()
            require(pos == src.length) { "Trailing characters after JSON object" }
            return map
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < src.length) { "Unterminated escape sequence" }
                        val esc = src[pos++]
                        when (esc) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= src.length) { "Invalid unicode escape" }
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parsePrimitive(): Any {
            return when (val ch = peek()) {
                '"' -> parseString()
                't' -> {
                    require(src.startsWith("true", pos)) { "Invalid token at $pos" }
                    pos += 4
                    true
                }
                'f' -> {
                    require(src.startsWith("false", pos)) { "Invalid token at $pos" }
                    pos += 5
                    false
                }
                '-', in '0'..'9' -> parseLong()
                else -> throw IllegalArgumentException("Unexpected value '$ch' at $pos")
            }
        }

        private fun parseLong(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            return src.substring(start, pos).toLong()
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun expect(expected: Char) {
            require(pos < src.length && src[pos] == expected) {
                "Expected '$expected' at position $pos"
            }
            pos++
        }

        private fun peek(): Char {
            require(pos < src.length) { "Unexpected end of JSON input" }
            return src[pos]
        }
    }
}
