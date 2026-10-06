package com.tracel.plugin.integration.worldedit

import com.tracel.plugin.services.TracelServices
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit

private val logger = Logger.getLogger("WorldEditSupport")

/** Plugin names that bring the `WorldEdit` API. `FAWE` is first: when both are there, `FAWE` is the one running. */
private val PLUGINS = listOf(WorldEditSupport.FAWE, "WorldEdit")

/** What `FAWE` compares an extent's class name against: any entry of `extent.allowed-plugins` it contains is let through. */
private const val EXTENT_PACKAGE = "com.tracel.plugin"

/**
 * Starts logging `WorldEdit` / `FAWE` edits when one of them is on the server.
 *
 * Nothing here touches a `WorldEdit` class: they are only loaded by [WorldEditHook], and only once a
 * `WorldEdit` plugin is known to be enabled, so a server without one never sees a missing class.
 */
internal object WorldEditSupport {
    /** `FAWE`'s plugin name. */
    const val FAWE = "FastAsyncWorldEdit"

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
        if (found == FAWE) allowExtents()
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

    /**
     * `FAWE` drops an extent a plugin wraps around it, unless the extent's class is named in `extent.allowed-plugins`.
     * `Tracel` wraps one where `FAWE` writes block by block, so it adds itself to that list, in memory.
     */
    private fun allowExtents() {
        try {
            val loader = Bukkit.getPluginManager().getPlugin(FAWE)?.javaClass?.classLoader ?: return
            val settingsClass = Class.forName("com.fastasyncworldedit.core.configuration.Settings", true, loader)
            val extent = settingsClass.getField("EXTENT").get(settingsClass.getMethod("settings").invoke(null))
            val field = extent.javaClass.getField("ALLOWED_PLUGINS")
            val allowed = (field.get(extent) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val already = allowed.any { it.isNotBlank() && EXTENT_CLASS.contains(it.lowercase()) }
            if (already) return
            try {
                @Suppress("UNCHECKED_CAST")
                (field.get(extent) as MutableList<String>).add(EXTENT_PACKAGE)
            } catch (_: UnsupportedOperationException) {
                field.set(extent, ArrayList(allowed) + EXTENT_PACKAGE)
            }
        } catch (failure: Throwable) {
            logger.warning(
                "Tracel could not add itself to FAWE's extent.allowed-plugins ($failure). Edits FAWE makes block " +
                    "by block are not logged until `$EXTENT_PACKAGE` is added to extent.allowed-plugins in " +
                    "FastAsyncWorldEditэ' configuration."
            )
        }
    }

    /** Whether [name] is one of the plugins [attach] looks for. */
    fun isWorldEdit(name: String): Boolean = name in PLUGINS
}

/** The class `FAWE` sees, lowercased the way it compares: the extent the hook wraps with. */
private const val EXTENT_CLASS = "com.tracel.plugin.integration.worldedit.worldedithook\$loggingextent"
