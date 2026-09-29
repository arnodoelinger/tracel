package com.tracel.plugin.command.action

import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.args.ActionFilter
import com.tracel.plugin.command.args.FilterResult
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.args.RollbackArgument
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.rollback.trace.PhaseTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Whose undo stack a job lands on: a player's own, or the console's. */
internal fun CommandSender.actor(): HolderId? = (this as? Player)?.let { HolderId.Player(it.uniqueId) }

/** Action responsible for orchestrating rollback operations, retries, and previews. */
// TODO: improve this in future
class RollbackAction internal constructor(
    private val services: TracelServices,
    private val highlights: Highlights? = null,
) {
    /**
     * Orchestrates rollback operations, retries, and previews, and then
     * rolls back what needed.
     */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (services.composite.isRunning) {
            sender.sendMessage("Rollback: another rollback or undo is currently running — wait for it to finish.")
            return
        }

        // TODO: add more guards

        if (parsed.errors.isNotEmpty()) {
            parsed.errors.forEach { sender.sendMessage("Rollback: $it") }
            RollbackPresenter.usage(sender)
            return
        }

        if (parsed.structureOnly && parsed.materialOnly) {
            sender.sendMessage("Rollback: #blocks and #items are opposites — give one or neither.")
            return
        }

        when (val filter = RollbackArgument.build(sender, parsed, limit = Int.MAX_VALUE)) {
            is FilterResult.Rejected -> {
                filter.reasons.forEach { sender.sendMessage("Rollback: $it") }
                RollbackPresenter.usage(sender)
            }

            is FilterResult.Ok -> if (!services.composite.claimGate()) {
                sender.sendMessage("Rollback: another rollback or undo is currently running — wait for it to finish.")
            } else services.scope.launch {
                try {
                    rollbackFiltered(sender, parsed, filter)
                } finally {
                    services.composite.releaseGate()
                }
            }
        }
    }

    private suspend fun rollbackFiltered(sender: CommandSender, parsed: ParsedLookupArgs, filter: FilterResult.Ok) {
        val trace = PhaseTimings()
        try {
            val replan: suspend () -> Planned? = {
                try {
                    services.composite.plan(
                        filter.filter,
                        structure = !parsed.materialOnly && filter.actions.structural,
                        material = !parsed.structureOnly && filter.actions.material,
                        trace = trace,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    sender.sendMessage("Rollback: could not work out what to do — ${failure.message ?: failure::class.java.simpleName}")
                    null
                }
            }
            val planned = replan() ?: return
            val halves = halvesOf(parsed, filter.actions)
            if (parsed.preview) {
                RollbackPresenter.preview(sender, planned, halves, services.entityRestoreLimit)
                if (sender is Player && highlights != null) {
                    val ghosts = highlights.ghost(sender, planned.composite.create + planned.composite.destroy, GHOST_SECONDS)
                    if (ghosts > 0) sender.sendMessage("  Ghost: $ghosts blocks shown to you for ${GHOST_SECONDS}s — only you see them, the world is untouched.")
                }
            } else if (!askedAboutEntities(sender, planned, parsed.confirmed)) {
                runRollback(sender, planned, halves, parsed.strict, replan)
            }
        } finally {
            trace.render().forEach(sender::sendMessage)
        }
    }

    private fun halvesOf(parsed: ParsedLookupArgs, actions: ActionFilter): String {
        val structure = !parsed.materialOnly && actions.structural
        val material = !parsed.structureOnly && actions.material
        return when {
            structure && material -> "blocks and items"
            structure -> "blocks only"
            material -> "items only"
            else -> "neither half"
        }
    }

    private suspend fun runRollback(
        sender: CommandSender,
        planned: Planned,
        halves: String,
        strict: Boolean,
        replan: suspend () -> Planned?,
    ) {
        var attempt = planned
        repeat(STALE_ATTEMPTS) {
            if (attempt.composite.isEmpty) {
                sender.sendMessage("Rollback: nothing matches (looked at $halves).")
                RollbackPresenter.reportPlaced(sender, attempt)
                return
            }
            val outcome = try {
                services.composite.apply(attempt.copy(by = sender.actor()), strict)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.sendMessage("Rollback failed: ${failure.message}")
                return
            }
            when (outcome) {
                is RollbackResult.Done -> {
                    if (RollbackPresenter.appliedNothing(outcome)) services.atomically {
                        services.jobs.markUndone(
                            outcome.job
                        )
                    }
                    RollbackPresenter.report(sender, outcome)
                    return
                }

                is Unreachable -> {
                    sender.sendMessage("Rollback: cannot touch ${outcome.holder} — ${outcome.reason}. Nothing was changed.")
                    return
                }

                is Blocked -> {
                    sender.sendMessage("Rollback: ${RollbackPresenter.leaseConflicts(outcome.conflicts)}")
                    return
                }

                RollbackResult.Stale -> attempt = replan() ?: return
            }
        }
        sender.sendMessage(
            "Rollback: the world kept changing under it ($STALE_ATTEMPTS attempts). " +
                    "Something is still moving these items — stop the redstone and run it again.",
        )
    }

    private fun askedAboutEntities(sender: CommandSender, planned: Planned, confirmed: Boolean): Boolean {
        if (confirmed) return false
        val spawns = planned.composite.create.resurrections()
        if (spawns.size <= services.entityRestoreLimit) return false
        sender.sendMessage(
            "Rollback: this would bring ${spawns.size} entities back (${mostly(spawns)}), past the " +
                    "${services.entityRestoreLimit} this server allows in one go."
        )
        sender.sendMessage("  Narrow the window or the scope, or run the same line again with #confirm.")
        sender.sendMessage("  With #confirm, t: counts back from that moment, so the count can shift a little.")
        return true
    }

    companion object {
        const val STALE_ATTEMPTS = 3
        const val GHOST_SECONDS = 12
    }
}
