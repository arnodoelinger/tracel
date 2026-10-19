package com.tracel.plugin.api

import com.tracel.api.TracelSession
import com.tracel.api.access.AccessError
import com.tracel.api.access.TracelConnection
import com.tracel.api.history.HistoryReads
import com.tracel.api.internal.ApiBackend
import com.tracel.api.internal.ApiHolder
import com.tracel.api.result.Res
import com.tracel.plugin.api.access.PluginConnection
import com.tracel.plugin.api.addon.AddonManager
import com.tracel.plugin.api.read.TracelReads
import com.tracel.plugin.api.rollback.Borrows
import com.tracel.plugin.services.TracelServices
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import org.bukkit.plugin.Plugin

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

    private val addons = AddonManager(plugin.logger) { name -> plugin.server.pluginManager.isPluginEnabled(name) }

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
        val connection = PluginConnection(plugin, services, borrows, reads, { running }, connections::remove)
        connections += connection
        return Res.Ok(connection)
    }

    override fun registerAddon(addon: Any) = addons.register(addon)

    override fun session(name: String): TracelSession = OwnedSession(name, Owned { resource, failure ->
        plugin.logger.log(Level.WARNING, "Tracel session \"$name\" failed to close $resource.", failure)
    })

    private class OwnedSession(override val name: String, private val owned: Owned) : TracelSession {
        override val isClosed: Boolean get() = owned.isClosed

        override fun <T : AutoCloseable> own(resource: T): T = owned.own(resource)

        override fun close() = owned.close()
    }
}
