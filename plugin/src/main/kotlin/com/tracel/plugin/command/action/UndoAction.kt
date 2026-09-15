package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender

/** Action responsible for taking back the most recent rollback. */
class UndoAction(private val services: TracelServices) {
    /** Undoes rollback. */
    fun execute(sender: CommandSender) {
        if (services.composite.isRunning) {
            sender.sendMessage("Rollback: another rollback or undo is currently running — wait for it to finish.")
            return
        }

        services.scope.launch {
            runUndo(sender)
        }
    }

    private suspend fun runUndo(sender: CommandSender) {
        val job = services.composite.lastUndoable()
        if (job == null) {
            sender.sendMessage("Undo: nothing to undo — no rollback has run that has not already been taken back.")
            return
        }

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
                    "Undo: rollback ${job.raw} is not the most recent one. Take back ${outcome.newer.size} " +
                            "newer job(s) first ($listed$more) — /tracel undo, repeated, does them in order."
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

    private companion object {
        const val LISTED_JOBS = 5
    }
}
