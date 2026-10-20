package com.tracel.plugin.api

import com.tracel.api.TracelSession
import com.tracel.api.access.AccessError
import com.tracel.api.access.TracelConnection
import com.tracel.api.history.HistoryReads
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.RecordKind
import com.tracel.api.internal.ApiBackend
import com.tracel.api.internal.ApiHolder
import com.tracel.api.result.Res
import com.tracel.plugin.api.access.PluginConnection
import com.tracel.plugin.api.addon.AddonManager
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.api.addon.HandlerBinder
import com.tracel.plugin.api.addon.ServiceWrites
import com.tracel.plugin.api.read.TracelReads
import com.tracel.plugin.api.rollback.Borrows
import com.tracel.plugin.services.TracelServices
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bukkit.plugin.Plugin
import kotlin.time.Duration.Companion.milliseconds

private const val FEED_RETRY_MILLIS = 5_000L

/** What stands behind `Tracel`'s public API while the plugin runs. */
internal class TracelBackend(
    private val plugin: Plugin,
    private val services: TracelServices,
) : ApiBackend, AutoCloseable {
    @Volatile
    private var running = false

    override val reads: HistoryReads = TracelReads(services, open = { running })

    private val borrows = Borrows()
    private val connections: MutableSet<PluginConnection> = ConcurrentHashMap.newKeySet()

    private val addons = AddonManager(
        plugin.logger,
        isPluginEnabled = { name -> plugin.server.pluginManager.isPluginEnabled(name) },
        extensions = Extensions.live,
        writes = ServiceWrites(services, Extensions.live) { running },
        binders = listOf(HandlerBinder(Extensions.live, ::feed, plugin.logger)),
        connect = { addon -> connection(ownerOf(addon)) },
        excused = services.api::excuses,
    )

    private fun ownerOf(addon: Any): Plugin {
        val loader = addon.javaClass.classLoader
        return plugin.server.pluginManager.plugins.firstOrNull { it.javaClass.classLoader === loader } ?: plugin
    }

    private fun connection(owner: Plugin): PluginConnection =
        PluginConnection(owner, services, borrows, reads, { running }, connections::remove).also { connections += it }

    /** Publishes the API and enables the addons that registered early. */
    fun open() {
        running = true
        addons.start()
        ApiHolder.attach(this)
    }

    /** Disables every addon, then withdraws the API. */
    override fun close() {
        ApiHolder.detach()
        running = false
        connections.toList().forEach(PluginConnection::close)
        addons.stop()
    }

    override fun connect(plugin: Plugin): Res<TracelConnection, AccessError> {
        if (!plugin.isEnabled) return Res.Err(AccessError.NotEnabled(plugin.name))
        return Res.Ok(connection(plugin))
    }

    override fun registerAddon(addon: Any) = addons.register(addon)

    private fun feed(only: Set<RecordKind>, sink: (HistoryRecord) -> Unit): AutoCloseable {
        val job = services.scope.launch {
            while (isActive) {
                try {
                    reads.watch { if (only.isNotEmpty()) only(*only.toTypedArray()) }.collect { sink(it) }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    plugin.logger.log(Level.WARNING, "An addon's record feed failed; trying again.", failure)
                    delay(FEED_RETRY_MILLIS.milliseconds)
                }
            }
        }
        return AutoCloseable { job.cancel() }
    }

    override fun session(name: String): TracelSession = OwnedSession(name, Owned { resource, failure ->
        plugin.logger.log(Level.WARNING, "Tracel session \"$name\" failed to close $resource.", failure)
    })

    private class OwnedSession(override val name: String, private val owned: Owned) : TracelSession {
        override val isClosed: Boolean get() = owned.isClosed

        override fun <T : AutoCloseable> own(resource: T): T = owned.own(resource)

        override fun close() = owned.close()
    }
}
