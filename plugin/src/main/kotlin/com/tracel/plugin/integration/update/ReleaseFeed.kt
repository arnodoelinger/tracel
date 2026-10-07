package com.tracel.plugin.integration.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.toVersionOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val FEED = "https://api.github.com/repos/arnodoelinger/Tracel/releases?per_page=20"

private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/** The releases `GitHub` lists for `Tracel`. */
internal object ReleaseFeed {
    /**
     * The newest release that is newer than [current], if there is one.
     *
     * @throws [IOException] if `GitHub` cannot be asked
     */
    suspend fun newerThan(current: Version): Release? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(FEED))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Tracel")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("GitHub answered ${response.statusCode()}")
        newest(response.body(), current)
    }

    /**
     * Picks the newest release in [body] (the feed's JSON) that beats [current].
     *
     * A pre-release is only offered to someone already on one: a stable server is not nagged about an alpha.
     */
    fun newest(body: String, current: Version): Release? {
        val releases = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: return null
        return releases.asSequence()
            .mapNotNull { it.asJsonObject.takeIf { json -> !json.flag("draft") } }
            .mapNotNull { json ->
                val version = json["tag_name"]?.asString?.removePrefix("v")?.toVersionOrNull(strict = false)
                    ?: return@mapNotNull null
                if (version.isPreRelease && !current.isPreRelease) return@mapNotNull null
                val page = json["html_url"]?.asString ?: return@mapNotNull null
                val jar = json["assets"]?.asJsonArray
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it["name"]?.asString?.endsWith(".jar") == true }
                    ?.get("browser_download_url")?.asString
                Release(version, page, jar ?: page)
            }
            .filter { it.version > current }
            .maxByOrNull { it.version }
    }

    private fun JsonObject.flag(name: String): Boolean = this[name]?.let(JsonElement::getAsBoolean) == true
}
