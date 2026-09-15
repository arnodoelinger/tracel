package com.tracel.plugin.command.action

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.structure.CompositeRollbackPlan
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.command.args.ActionFilter
import com.tracel.plugin.command.args.FilterResult
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.args.RollbackArgument
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Action responsible for orchestrating rollback operations, retries, and previews. */
// TODO: improve this in future
class RollbackAction(
    private val services: TracelServices,
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

        val lot = parsed.lot
        if (lot != null) {
            val restoreTo = (sender as? Player)?.let { HolderId.Player(it.uniqueId) }
            if (restoreTo == null) {
                sender.sendMessage("Rollback: l:$lot needs somewhere to put the material — run it as a player.")
                return
            }
            services.scope.launch {
                byLot(sender, LotId(lot), restoreTo, parsed)
            }
            return
        }

        when (val filter = RollbackArgument.build(sender, parsed, limit = Int.MAX_VALUE)) {
            is FilterResult.Rejected -> {
                filter.reasons.forEach { sender.sendMessage("Rollback: $it") }
                RollbackPresenter.usage(sender)
            }

            is FilterResult.Ok -> services.scope.launch {
                val replan: suspend () -> Planned? = {
                    try {
                        services.composite.plan(
                            filter.filter,
                            structure = !parsed.materialOnly && filter.actions.structural,
                            material = !parsed.structureOnly && filter.actions.material,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        sender.sendMessage("Rollback: could not work out what to do — ${failure.message ?: failure::class.java.simpleName}")
                        null
                    }
                }
                val planned = replan() ?: return@launch
                val halves = halvesOf(parsed, filter.actions)
                if (parsed.preview) {
                    RollbackPresenter.preview(sender, planned, halves, services.entityRestoreLimit)
                } else if (!askedAboutEntities(sender, planned, parsed.confirmed)) {
                    runRollback(sender, planned, halves, parsed.strict, replan)
                }
            }
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

    private suspend fun byLot(sender: CommandSender, lot: LotId, restoreTo: HolderId.Player, parsed: ParsedLookupArgs) {
        val outcome = runCatching {
            RollbackPlanner(services.repo, services.worldQuery, structural = false).plan(listOf(lot))
        }
        val plan = outcome.getOrNull()
        if (plan == null) {
            sender.sendMessage("Rollback: could not plan for lot ${lot.raw} — ${outcome.exceptionOrNull()?.message}")
            return
        }

        fun rollbackFor(fresh: RollbackPlan) = Planned(
            CompositeRollbackPlan(emptyList(), fresh, emptyList()),
            RollbackTarget.Uniform(restoreTo),
            listOf(lot),
        )

        val planned = rollbackFor(plan)
        val replan: suspend () -> Planned? = {
            runCatching {
                RollbackPlanner(services.repo, services.worldQuery, structural = false).plan(listOf(lot))
            }.map(::rollbackFor).getOrElse {
                sender.sendMessage("Rollback: could not plan for lot ${lot.raw} — ${it.message}")
                null
            }
        }
        if (parsed.preview) {
            RollbackPresenter.preview(sender, planned, "items only", services.entityRestoreLimit)
        } else {
            runRollback(sender, planned, "items only", parsed.strict, replan)
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
                services.composite.apply(attempt, strict)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.sendMessage("Rollback failed: ${failure.message}")
                return
            }
            when (outcome) {
                is RollbackResult.Done -> {
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
        return true
    }

    companion object {
        const val STALE_ATTEMPTS = 3
    }
}
