package com.tracel.plugin.integration.luckperms

import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The tick to wait for, so the permission plugin has finished before we ask it. */
private const val SETTLE_TICKS = 1L

/**
 * What each online player was last allowed to do with `Tracel`, to resend the command tree only when that changed.
 *
 * The client builds its tab completion from the tree it was sent on join, so a permission granted later stays
 * invisible to it until the tree is sent again.
 */
internal class PermissionSnapshots(private val plugin: Plugin) {
    private val seen = ConcurrentHashMap<UUID, Int>()

    /** Remembers what [player] may do right now. */
    fun remember(player: Player) {
        seen[player.uniqueId] = maskOf(player)
    }

    /** Drops [player], who is gone. */
    fun forget(player: UUID) {
        seen.remove(player)
    }

    /** Looks at [player] again on their own thread, and resends their command tree if the answer moved. */
    fun refresh(player: Player) {
        player.scheduler.runDelayed(plugin, { _ ->
            val now = maskOf(player)
            val before = seen.replace(player.uniqueId, now) ?: return@runDelayed
            if (before != now) player.updateCommands()
        }, null, SETTLE_TICKS)
    }

    private fun maskOf(player: Player): Int =
        Permission.entries.fold(0) { mask, permission -> if (player.has(permission)) mask or (1 shl permission.ordinal) else mask }
}
