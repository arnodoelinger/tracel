package com.tracel.plugin.integration.luckperms

import com.tracel.plugin.listener.registerObserved
import com.tracel.plugin.services.TracelServices
import org.bukkit.Bukkit
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("LuckPermsSupport")

/** `LuckPerms`'s plugin name. */
private const val LUCKPERMS = "LuckPerms"

/**
 * Hooks into `LuckPerms` when it is on the server.
 *
 * Nothing here touches a `LuckPerms` class: they are only loaded by [LuckPermsHook], and only once the plugin is
 * known to be enabled, so a server without it never sees a missing class.
 */
internal object LuckPermsSupport {
    /** Attaches the hook if `LuckPerms` is enabled. Without it, permissions still work: only the tab completion lags. */
    fun attach(services: TracelServices) {
        if (!Bukkit.getPluginManager().isPluginEnabled(LUCKPERMS)) return
        val plugin = services.plugin
        try {
            val snapshots = PermissionSnapshots(plugin)
            val hook = LuckPermsHook(plugin, snapshots)
            hook.register()
            registerObserved(PermissionSnapshotListener(snapshots), plugin)
            services.luckPerms = hook
            logger.info("Following permission changes made with $LUCKPERMS.")
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "Tracel will not follow $LUCKPERMS permission changes live: $failure.", failure)
        }
    }
}
