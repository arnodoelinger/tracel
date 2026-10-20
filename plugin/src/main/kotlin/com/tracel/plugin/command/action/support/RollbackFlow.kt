package com.tracel.plugin.command.action.support

import com.tracel.api.actor.Actor
import com.tracel.api.world.NamespacedName
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.i18n.confirmHint
import com.tracel.plugin.i18n.needed
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.services.TracelServices
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Whose undo stack a job lands on: a player's own, or the console's. */
internal fun CommandSender.actor(): Actor.Player? = (this as? Player)?.let { Actor.Player(it.uniqueId) }

/**
 * Asks [sender] to confirm when [spawns] brings back more entities than the limit; returns whether it asked.
 *
 * [spawns] is only read when [confirmed] is not set, because for an undo it costs a read.
 */
internal suspend fun TracelServices.askedAboutEntities(
    sender: CommandSender,
    confirmed: Boolean,
    spawns: suspend () -> Map<NamespacedName, Int>,
): Boolean {
    if (confirmed) return false
    val found = spawns()
    val count = found.values.sum()
    if (count <= entityRestoreLimit) return false
    sender.needed(
        info = tr(
            "rollback.entities",
            "count" to count,
            "mostly" to mostly(found),
            "limit" to entityRestoreLimit,
        ),
        hint = confirmHint(),
    )
    return true
}
