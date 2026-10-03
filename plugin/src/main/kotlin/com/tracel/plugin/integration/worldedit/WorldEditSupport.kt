package com.tracel.plugin.integration.worldedit

import com.tracel.annotations.Observes
import com.tracel.plugin.TracelServices
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("WorldEditSupport")

/** Plugin names that bring the `WorldEdit` API. `FAWE` is first: when both are there, `FAWE` is the one running. */
private val PLUGINS = listOf("FastAsyncWorldEdit", "WorldEdit")

/**
 * Starts logging `WorldEdit` / `FAWE` edits when one of them is on the server.
 *
 * Nothing here touches a `WorldEdit` class: they are only loaded by [WorldEditHook], and only once a
 * `WorldEdit` plugin is known to be enabled, so a server without one never sees a missing class.
 */
internal object WorldEditSupport {
    /**
     * Attaches the hook if it can and should be. Safe to call again.
     *
     * @return `true` if a hook is attached afterwards.
     */
    @Synchronized
    fun attach(services: TracelServices): Boolean {
        if (services.worldEdit != null) return true
        if (!services.logging.blocks || !services.logging.worldEdit) return false
        val found = PLUGINS.firstOrNull { Bukkit.getPluginManager().isPluginEnabled(it) } ?: return false
        return try {
            val hook = WorldEditHook(services)
            hook.register()
            services.worldEdit = hook
            logger.info("Logging edits made with $found.")
            true
        } catch (failure: Throwable) {
            val why = if (failure is LinkageError) {
                "its API is not the one Tracel was built against (${failure.message})"
            } else {
                failure.toString()
            }
            logger.log(Level.WARNING, "Edits made with $found will not be logged: $why", failure)
            false
        }
    }

    /** Whether [name] is one of the plugins [attach] looks for. */
    fun isWorldEdit(name: String): Boolean = name in PLUGINS
}

/** Catches `WorldEdit` being enabled after `Tracel` did, which the load order is meant to prevent but cannot always. */
internal class WorldEditAttachListener(private val services: TracelServices) : Listener {
    /** When a `WorldEdit` plugin is enabled, try to attach the hook. */
    @Observes
    fun onEnable(event: PluginEnableEvent) {
        if (WorldEditSupport.isWorldEdit(event.plugin.name)) WorldEditSupport.attach(services)
    }
}
