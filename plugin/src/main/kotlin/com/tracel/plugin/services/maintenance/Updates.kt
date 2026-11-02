package com.tracel.plugin.services.maintenance

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tracel.annotations.Observes
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.say
import com.tracel.plugin.util.tr
import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.toVersionOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tells operators who join that this `Tracel` is a development build, and what that means. */
internal class DevBuildListener(private val version: String, private val plugin: Plugin) : Listener {
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.has(Permission.STATUS)) return
        player.scheduler.runDelayed(plugin, { _ ->
            player.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("update.dev_title", "version" to version),
                    Component.empty(),
                    tr("update.ran", "version" to version),
                    tr("update.dev_advice"),
                )
            )
        }, null, SHOW_AFTER_TICKS)
    }
}

/**
 * A published `Tracel` release.
 *
 * @param page the release page on Modrinth, with the changes
 * @param download where the .jar is taken from, which is [page] too: Modrinth has the download button there
 */
data class Release(val version: Version, val page: String, val download: String)

private const val FEED = "https://api.github.com/repos/arnodoelinger/Tracel/releases?per_page=20"
private const val MODRINTH = "https://modrinth.com/plugin/tracel"

private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/** A build made from the source tree, ahead of every release it is named after: there is nothing to update to. */
internal fun Version.isDevelopment(): Boolean = preRelease?.startsWith("dev", ignoreCase = true) == true

/** Whether [version], as `plugin.yml` names it, is a development build. */
internal fun isDevelopmentBuild(version: String): Boolean = version.toVersionOrNull(strict = false)?.isDevelopment() == true

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
                val page = "$MODRINTH/version/$version"
                Release(version, page, page)
            }
            .filter { it.version > current }
            .maxByOrNull { it.version }
    }

    private fun JsonObject.flag(name: String): Boolean = this[name]?.let(JsonElement::getAsBoolean) == true
}

/** How long between two looks at `GitHub`. */
private val EVERY = 12.hours

/** Looks for a newer `Tracel` now and then, and remembers the newest it found. */
internal class UpdateChecker(private val services: TracelServices) {
    /** The release to tell operators about, or `null` if this one is the newest known. */
    @Volatile
    var available: Release? = null; private set

    /** The version this server runs. */
    val running: String = services.plugin.pluginMeta.version

    /** Starts the periodic check. A server whose version cannot be read has nothing to compare, so it does not start. */
    fun start() {
        val current = running.toVersionOrNull(strict = false) ?: return
        if (current.isDevelopment()) return
        services.scope.launch {
            while (isActive) {
                try {
                    val found = ReleaseFeed.newerThan(current)
                    if (found != null && found.version != available?.version) {
                        services.plugin.logger.info(
                            "Update available: $running -> ${found.version}; ${found.page}"
                        )
                    }
                    available = found
                } catch (failure: Exception) {
                    services.plugin.logger.log(Level.FINE, "Could not look for a Tracel update: $failure")
                }
                delay(EVERY)
            }
        }
    }
}

private const val SHOW_AFTER_TICKS = 40L

/** Tells operators who join that a newer `Tracel` is out. */
internal class UpdateListener(private val checker: UpdateChecker, private val plugin: Plugin) : Listener {
    /** Shows the notice to whoever may see the status panel. */
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (checker.available == null || !player.has(Permission.STATUS)) return
        player.scheduler.runDelayed(plugin, { _ ->
            val release = checker.available ?: return@runDelayed
            player.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("update.title", "version" to release.version),
                    Component.empty(),
                    tr("update.ran", "version" to checker.running),
                    tr("update.advice"),
                    Component.empty(),
                    Component.join(
                        JoinConfiguration.separator(Component.text("  ")),
                        tr("update.download")
                            .hoverEvent(HoverEvent.showText(tr("update.download_hover")))
                            .clickEvent(ClickEvent.openUrl(release.download)),
                        tr("update.news")
                            .hoverEvent(HoverEvent.showText(tr("update.news_hover")))
                            .clickEvent(ClickEvent.openUrl(release.page)),
                    ),
                )
            )
        }, null, SHOW_AFTER_TICKS)
    }
}
