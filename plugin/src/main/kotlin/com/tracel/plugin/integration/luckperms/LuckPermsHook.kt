package com.tracel.plugin.integration.luckperms

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.user.UserDataRecalculateEvent
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Resends the command tree to a player when `LuckPerms` changes what they may do with `Tracel`.
 *
 * Without it, a freshly granted `tracel.rollback` works at once but stays out of tab completion until the
 * player rejoins.
 */
internal class LuckPermsHook(private val plugin: Plugin, private val snapshots: PermissionSnapshots) : AutoCloseable {
    private var subscription: EventSubscription<UserDataRecalculateEvent>? = null

    /** Starts listening to `LuckPerms`. */
    fun register() {
        Bukkit.getOnlinePlayers().forEach(snapshots::remember)
        val bus = LuckPermsProvider.get().eventBus
        subscription = bus.subscribe(plugin, UserDataRecalculateEvent::class.java) { event ->
            Bukkit.getPlayer(event.user.uniqueId)?.let(snapshots::refresh)
        }
    }

    override fun close() {
        subscription?.close()
        subscription = null
    }
}
