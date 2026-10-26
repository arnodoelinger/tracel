package com.tracel.storage.codec.records

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** `gzip` members kept inflated. */
internal object Gzipped {
    /**
     * The inflated form of [bytes], or `null` if they are not one `gzip` member that
     * deflates back byte for byte.
     */
    fun inflate(bytes: ByteArray, limit: Int): ByteArray? {
        if (bytes.size < 18 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte() || bytes[2] != 8.toByte()) return null
        val plain = runCatching {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream(bytes.size * 4)
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > limit) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        return plain.takeIf { deflate(it).contentEquals(bytes) }
    }

    /** [plain] gzipped the way the JDK does it, which is the way it came in. */
    fun deflate(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size / 3 + 32)
        GZIPOutputStream(out).use { it.write(plain) }
        return out.toByteArray()
    }
}
