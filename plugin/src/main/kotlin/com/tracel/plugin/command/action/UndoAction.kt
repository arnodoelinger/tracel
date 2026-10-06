package com.tracel.plugin.command.action

import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.command.presenter.line.ChangeLinePresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

/** Action responsible for taking back the most recent rollback. */
class UndoAction internal constructor(private val services: TracelServices, private val nothing: NothingWeCanDo) {
    /** Undoes rollback. */
    fun execute(sender: CommandSender, confirmed: Boolean = false, job: Long? = null) {
        if (!services.composite.claimGate()) {
            sender.send("common.busy")
            return
        }

        services.scope.launch {
            try {
                services.purgeGate.awaitSlice()
                runUndo(sender, confirmed, job?.let(::RollbackJobId))
            } finally {
                services.composite.releaseGate()
            }
        }
    }

    private suspend fun runUndo(sender: CommandSender, confirmed: Boolean, requested: RollbackJobId?) {
        if (requested != null && !services.composite.isUndoable(sender.actor(), requested)) {
            RollbackPresenter.refusedUndo(sender, tr("undo.reason.already"), nothing.on(tr("common.nothing")))
            return
        }
        val job = requested ?: services.composite.lastUndoable(sender.actor())
        if (job == null) {
            RollbackPresenter.refusedUndo(sender, tr("undo.reason.nothing"), tr("undo.hint.nothing"))
            return
        }
        if (!confirmed && askedAboutEntities(sender, job)) return

        val started = System.nanoTime()
        val outcome = try {
            services.composite.undo(job)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Telemetry.undo("failed")
            RollbackPresenter.refusedUndo(sender, Component.text(unexpected(failure)), tr("rollback.hint.again"))
            return
        }
        val took = (System.nanoTime() - started) / 1_000_000
        Telemetry.undo(
            when (outcome) {
                is UndoResult.Done ->
                    if (outcome.structure.fullyRestored && outcome.material.fullyRestored) "clean" else "partial"

                UndoResult.NotFound -> "not found"
                is UndoResult.OutOfOrder -> "out of order"
                UndoResult.AlreadyUndone -> "already undone"
                is Unreachable -> "unreachable"
                is Blocked -> "blocked"
                is UndoResult.Failed -> "failed"
            },
        )

        when (outcome) {
            is UndoResult.Done -> RollbackPresenter.reportUndo(sender, outcome, took)
            UndoResult.NotFound -> RollbackPresenter.refusedUndo(
                sender,
                tr("undo.reason.not_found"),
                tr("undo.hint.again")
            )

            is UndoResult.OutOfOrder ->
                RollbackPresenter.refusedUndo(sender, tr("undo.reason.out_of_order"), tr("undo.hint.out_of_order"))

            UndoResult.AlreadyUndone -> RollbackPresenter.refusedUndo(
                sender,
                tr("undo.reason.already"),
                nothing.on(tr("common.nothing"))
            )

            is Unreachable -> RollbackPresenter.refusedUndo(
                sender,
                tr(
                    "rollback.reason.unreachable",
                    "holder" to ChangeLinePresenter.holder(outcome.holder),
                    "reason" to outcome.reason
                ),
                tr("rollback.hint.unreachable"),
            )

            is Blocked -> RollbackPresenter.refusedUndo(
                sender,
                tr("rollback.reason.blocked"),
                tr("rollback.hint.blocked")
            )

            is UndoResult.Failed -> RollbackPresenter.refusedUndo(
                sender,
                Component.text(outcome.reason),
                tr("rollback.hint.again")
            )
        }
    }

    private suspend fun askedAboutEntities(sender: CommandSender, job: RollbackJobId): Boolean {
        val record = services.atomically { services.jobs.find(job) } ?: return false
        val spawns = (record.create + record.destroy).map { it.inverse() }.resurrections()
        if (spawns.size <= services.entityRestoreLimit) return false
        sender.needed(
            info = tr(
                "rollback.entities",
                "count" to spawns.size,
                "mostly" to mostly(spawns),
                "limit" to services.entityRestoreLimit
            ),
            hint = confirmHint(),
        )
        return true
    }
}
