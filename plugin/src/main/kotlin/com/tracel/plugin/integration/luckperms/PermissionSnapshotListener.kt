package com.tracel.plugin.integration.luckperms

import com.tracel.annotations.Observes
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Keeps [PermissionSnapshots] to the players who are online. */
internal class PermissionSnapshotListener(private val snapshots: PermissionSnapshots) : Listener {
    /** The tree they are sent on join matches what they may do now. */
    @Observes
    fun onJoin(event: PlayerJoinEvent) {
        snapshots.remember(event.player)
    }

    /** Nothing to compare against once they are gone. */
    @Observes
    fun onQuit(event: PlayerQuitEvent) {
        snapshots.forget(event.player.uniqueId)
    }
}
