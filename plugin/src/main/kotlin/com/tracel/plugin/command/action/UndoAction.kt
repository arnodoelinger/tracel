package com.tracel.plugin.command.action

import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender

/** Action responsible for taking back the most recent rollback. */
class UndoAction(private val services: TracelServices) {
    /** Undoes rollback. */
    fun execute(sender: CommandSender, confirmed: Boolean = false) {
        if (!services.composite.claimGate()) {
            sender.sendMessage("Rollback: another rollback or undo is currently running — wait for it to finish.")
            return
        }

        services.scope.launch {
            try {
                runUndo(sender, confirmed)
            } finally {
                services.composite.releaseGate()
            }
        }
    }

    private suspend fun runUndo(sender: CommandSender, confirmed: Boolean) {
        val job = services.composite.lastUndoable(sender.actor())
        if (job == null) {
            sender.sendMessage("Undo: nothing to undo — every rollback you ran has already been taken back.")
            return
        }
        if (!confirmed && askedAboutEntities(sender, job)) return

        val outcome = try {
            services.composite.undo(job)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            sender.sendMessage("Undo: rollback ${job.raw} could not be taken back — ${failure.message ?: failure::class.java.simpleName}")
            return
        }

        when (outcome) {
            is UndoResult.Done -> {
                sender.sendMessage(
                    "Undid rollback ${job.raw}: ${outcome.structure.count} block(s)/entity(s), " +
                            "${outcome.steps.size} ledger step(s)."
                )
                RollbackPresenter.reportProblems(
                    sender = sender,
                    queued = outcome.material.queued,
                    failures = outcome.material.failures,
                    structure = outcome.structure,
                    spilled = outcome.material.spilled,
                )
            }

            UndoResult.NotFound -> sender.sendMessage("Undo: no rollback job ${job.raw} is on record.")
            is UndoResult.OutOfOrder -> {
                val listed = outcome.newer.take(LISTED_JOBS).joinToString(", ") { it.raw.toString() }
                val more = if (outcome.newer.size > LISTED_JOBS) ", ..." else ""
                sender.sendMessage(
                    "Undo: ${outcome.newer.size} newer rollback(s) by someone else changed the same blocks or items " +
                            "($listed$more). Whoever ran them has to undo theirs first."
                )
            }

            UndoResult.AlreadyUndone -> sender.sendMessage("Undo: rollback ${job.raw} was already taken back.")
            is Unreachable ->
                sender.sendMessage("Undo: cannot touch ${outcome.holder} — ${outcome.reason}. Nothing was changed.")

            is Blocked ->
                sender.sendMessage("Undo: ${RollbackPresenter.leaseConflicts(outcome.conflicts)}")

            is UndoResult.Failed -> sender.sendMessage("Undo: rollback ${job.raw} could not be taken back — ${outcome.reason}")
        }
    }

    private suspend fun askedAboutEntities(sender: CommandSender, job: RollbackJobId): Boolean {
        val record = services.atomically { services.jobs.find(job) } ?: return false
        val spawns = (record.create + record.destroy).map { it.inverse() }.resurrections()
        if (spawns.size <= services.entityRestoreLimit) return false
        sender.sendMessage(
            "Undo: this would bring ${spawns.size} entities back (${mostly(spawns)}), past the " +
                    "${services.entityRestoreLimit} this server allows in one go."
        )
        sender.sendMessage("  Run /tracel restore #confirm to do it anyway.")
        return true
    }

    private companion object {
        const val LISTED_JOBS = 5
    }
}
