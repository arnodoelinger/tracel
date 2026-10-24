package com.tracel.plugin.command.action

import com.tracel.api.item.ItemHolder
import com.tracel.api.result.Res
import com.tracel.api.rollback.RollbackError
import com.tracel.api.rollback.RollbackJobId
import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.action.support.actor
import com.tracel.plugin.command.action.support.askedAboutEntities
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

/** Action responsible for taking back the most recent rollback. */
class UndoAction internal constructor(private val services: TracelServices, private val nothing: NothingWeCanDo) {
    /** Undoes rollback. */
    fun execute(sender: CommandSender, confirmed: Boolean = false, job: Long? = null) {
        services.scope.launch { runUndo(sender, confirmed, job?.let(::RollbackJobId)) }
    }

    private suspend fun runUndo(sender: CommandSender, confirmed: Boolean, requested: RollbackJobId?) {
        val rollbacks = services.commandApi.rollbacks(sender.actor(), confirmed)
        val job = requested ?: rollbacks.latest()
        if (job == null) {
            RollbackPresenter.refusedUndo(sender, tr("undo.reason.nothing"), tr("undo.hint.nothing"))
            return
        }
        if (!confirmed) {
            val spawns = when (val preview = rollbacks.undoPreview(job)) {
                is Res.Ok -> preview.value.entityTypes
                is Res.Err -> return refuse(sender, preview.error)
            }
            if (services.askedAboutEntities(sender, false) { spawns }) return
        }

        val started = System.nanoTime()
        val outcome = rollbacks.undo(job)
        val took = (System.nanoTime() - started) / 1_000_000
        when (outcome) {
            is Res.Ok -> {
                val undone = outcome.value
                Telemetry.undo(Telemetry.finish(undone.skipped, undone.itemsRestored))
                RollbackPresenter.reportUndo(sender, undone, took)
            }

            is Res.Err -> refuse(sender, outcome.error)
        }
    }

    private fun refuse(sender: CommandSender, error: RollbackError) {
        when (error) {
            RollbackError.Busy -> sender.send("common.busy")

            RollbackError.NotUndoable -> {
                Telemetry.undo("already undone")
                RollbackPresenter.refusedUndo(sender, tr("undo.reason.already"), nothing.on(tr("common.nothing")))
            }

            is RollbackError.OutOfOrder -> {
                Telemetry.undo("out of order")
                RollbackPresenter.refusedUndo(sender, tr("undo.reason.out_of_order"), tr("undo.hint.out_of_order"))
            }

            is RollbackError.Unreachable -> unreachable(sender, error.holder, error.reason)
            is RollbackError.AddonMissing -> unreachable(sender, error.holder, error.reason)

            is RollbackError.Blocked, is RollbackError.Borrowed -> {
                Telemetry.undo("blocked")
                RollbackPresenter.refusedUndo(sender, tr("rollback.reason.blocked"), tr("rollback.hint.blocked"))
            }

            is RollbackError.Failed -> failed(sender, error.message)
            is RollbackError.Unsupported -> failed(sender, error.reason)

            else -> failed(sender, error.toString())
        }
    }

    private fun unreachable(sender: CommandSender, holder: ItemHolder, reason: String) {
        Telemetry.undo("unreachable")
        RollbackPresenter.refusedUndo(
            sender,
            RollbackPresenter.unreachable(holder, reason),
            tr("rollback.hint.unreachable"),
        )
    }

    private fun failed(sender: CommandSender, message: String) {
        Telemetry.undo("failed")
        RollbackPresenter.refusedUndo(sender, Component.text(said(message)), tr("rollback.hint.again"))
    }
}
