package com.tracel.plugin.api

import com.tracel.api.TracelSession
import com.tracel.api.history.HistoryReads
import com.tracel.api.internal.ApiBackend
import com.tracel.api.internal.ApiHolder
import com.tracel.plugin.api.addon.AddonManager
import com.tracel.plugin.api.read.TracelReads
import com.tracel.plugin.services.TracelServices
import java.util.logging.Level
import org.bukkit.plugin.Plugin

/** What stands behind `Tracel`'s public API while the plugin runs. */
internal class TracelBackend(private val plugin: Plugin, services: TracelServices) : ApiBackend, AutoCloseable {
    override val reads: HistoryReads = TracelReads(services)

    private val addons = AddonManager(plugin.logger) { name -> plugin.server.pluginManager.isPluginEnabled(name) }

    /** Publishes the API and enables the addons that registered early. */
    fun open() {
        addons.start()
        ApiHolder.attach(this)
    }

    /** Disables every addon, then withdraws the API. */
    override fun close() {
        ApiHolder.detach()
        addons.stop()
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
