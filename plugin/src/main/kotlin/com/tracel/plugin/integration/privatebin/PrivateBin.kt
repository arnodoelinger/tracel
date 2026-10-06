package com.tracel.plugin.integration.privatebin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Text put on a `privatebin.net` server, encrypted here. */
class PrivateBin(server: URI, private val expire: String, private val burn: Boolean) {
    private val server: URI = URI.create(server.toString().trimEnd('/') + "/")

    /**
     * Uploads [text]; the link it returns is the only place the key exists.
     *
     * @throws [IOException] if the server says no
     */
    suspend fun upload(text: String): URI = withContext(Dispatchers.IO) {
        val random = SecureRandom()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val body =
            paste(text, key, ByteArray(IV_BYTES).also(random::nextBytes), ByteArray(SALT_BYTES).also(random::nextBytes))
        val request = HttpRequest.newBuilder(server)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "JSONHttpRequest")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }
            .getOrElse { throw IOException("the server answered ${response.statusCode()}, which is not a PrivateBin") }
        if (json["status"]?.asInt != 0) throw IOException(json["message"]?.asString ?: "the server refused it")
        URI.create("$server?${json["id"].asString}#${if (burn) "-" else ""}${base58(key)}")
    }

    /** The request body: [text] encrypted under [key], with the parameters it needs to be read back. */
    internal fun paste(text: String, key: ByteArray, iv: ByteArray, salt: ByteArray): JsonObject {
        val b64 = Base64.getEncoder()
        val adata =
            "[[\"${b64.encodeToString(iv)}\",\"${b64.encodeToString(salt)}\",$ITERATIONS,256,128,\"aes\",\"gcm\",\"none\"],\"plaintext\",0,${if (burn) 1 else 0}]"
        val plain = JsonObject().apply { addProperty("paste", text) }.toString().toByteArray(Charsets.UTF_8)
        val ct = encrypt(plain, key, iv, salt, adata.toByteArray(Charsets.UTF_8))
        return JsonObject().apply {
            addProperty("v", 2)
            add("adata", JsonParser.parseString(adata))
            addProperty("ct", b64.encodeToString(ct))
            add("meta", JsonObject().apply { addProperty("expire", expire) })
        }
    }

    internal companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 16
        const val SALT_BYTES = 8
        const val ITERATIONS = 100_000

        val EXPIRE_NAME = Regex("[a-z0-9_]+")

        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        /** AES-256-GCM with a 128-bit tag, which `Java` appends to the text as the browser expects. */
        fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray, salt: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pbkdf2(key, salt), "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(plain)
        }

        /** PBKDF2-HMAC-SHA256, one 32-byte block; written out because the JDK's wants characters and the key is bytes. */
        fun pbkdf2(password: ByteArray, salt: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
            val out = u.copyOf()
            repeat(ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
            }
            return out
        }

        /** Bitcoin-style base58, leading zero bytes kept as `1`: how the key sits in a link. */
        fun base58(bytes: ByteArray): String {
            val zeros = bytes.takeWhile { it.toInt() == 0 }.size
            var number = BigInteger(1, bytes)
            val out = StringBuilder()
            val base = BigInteger.valueOf(58)
            while (number.signum() > 0) {
                val (div, rem) = number.divideAndRemainder(base)
                out.append(ALPHABET[rem.toInt()])
                number = div
            }
            repeat(zeros) { out.append(ALPHABET[0]) }
            return out.reverse().toString()
        }
    }
}
