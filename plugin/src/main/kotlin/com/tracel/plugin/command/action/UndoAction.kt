package com.tracel.plugin.command.action

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.action.support.actor
import com.tracel.plugin.command.action.support.askedAboutEntities
import com.tracel.plugin.command.action.support.rescuing
import com.tracel.plugin.command.action.support.underGate
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.i18n.*
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

/** Action responsible for taking back the most recent rollback. */
class UndoAction internal constructor(private val services: TracelServices, private val nothing: NothingWeCanDo) {
    /** Undoes rollback. */
    fun execute(sender: CommandSender, confirmed: Boolean = false, job: Long? = null) {
        services.underGate(sender) { runUndo(sender, confirmed, job?.let(::RollbackJobId)) }
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
        if (services.askedAboutEntities(sender, confirmed) { spawnsOfUndo(job) }) return

        val started = System.nanoTime()
        val outcome = rescuing(
            onFailure = { failure ->
                Telemetry.undo("failed")
                RollbackPresenter.refusedUndo(sender, Component.text(unexpected(failure)), tr("rollback.hint.again"))
                return
            },
        ) {
            services.composite.undo(job)
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
                RollbackPresenter.unreachable(outcome),
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

    private suspend fun spawnsOfUndo(job: RollbackJobId): List<StructureStep.SpawnEntity> {
        val record = services.atomically { services.jobs.find(job) } ?: return emptyList()
        return (record.create + record.destroy).map { it.inverse() }.resurrections()
    }
}
