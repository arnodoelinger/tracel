package com.tracel.plugin.command.action.support

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.i18n.confirmHint
import com.tracel.plugin.i18n.needed
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Whose undo stack a job lands on: a player's own, or the console's. */
internal fun CommandSender.actor(): HolderId? = (this as? Player)?.let { HolderId.Player(it.uniqueId) }

/** Runs [body] holding the rollback gate, or says it is busy. The gate is let go whatever happens. */
internal fun TracelServices.underGate(sender: CommandSender, body: suspend () -> Unit) {
    if (!composite.claimGate()) {
        sender.send("common.busy")
        return
    }
    scope.launch {
        try {
            purgeGate.awaitSlice()
            body()
        } finally {
            composite.releaseGate()
        }
    }
}

/**
 * Asks [sender] to confirm when [spawns] brings back more entities than the limit; returns whether it asked.
 *
 * [spawns] is only read when [confirmed] is not set, because for an undo it costs a read.
 */
internal suspend fun TracelServices.askedAboutEntities(
    sender: CommandSender,
    confirmed: Boolean,
    spawns: suspend () -> List<StructureStep.SpawnEntity>,
): Boolean {
    if (confirmed) return false
    val found = spawns()
    if (found.size <= entityRestoreLimit) return false
    sender.needed(
        info = tr(
            "rollback.entities",
            "count" to found.size,
            "mostly" to mostly(found),
            "limit" to entityRestoreLimit,
        ),
        hint = confirmHint(),
    )
    return true
}

/** [block], or [onFailure] when it throws. Cancellation goes through: a stopped job is not failed. */
internal inline fun <T> rescuing(onFailure: (Throwable) -> T, block: () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onFailure(failure)
    }
