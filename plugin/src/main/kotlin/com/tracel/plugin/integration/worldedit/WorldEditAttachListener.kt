package com.tracel.plugin.integration.worldedit

import com.tracel.annotations.Observes
import com.tracel.plugin.services.TracelServices
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent

/** Catches `WorldEdit` being enabled after `Tracel` did, which the load order is meant to prevent but cannot always. */
internal class WorldEditAttachListener(private val services: TracelServices) : Listener {
    /** When a `WorldEdit` plugin is enabled, try to attach the hook. */
    @Observes
    fun onEnable(event: PluginEnableEvent) {
        if (WorldEditSupport.isWorldEdit(event.plugin.name)) WorldEditSupport.attach(services)
    }
}
