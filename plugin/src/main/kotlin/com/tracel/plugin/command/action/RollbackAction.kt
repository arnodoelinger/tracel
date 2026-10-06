package com.tracel.plugin.command.action

import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.rollback.composer.warmForPreview
import com.tracel.plugin.command.args.action.ActionFilter
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.rollback.FilterResult
import com.tracel.plugin.command.args.rollback.RollbackArgument
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter.mostly
import com.tracel.plugin.command.presenter.RollbackPresenter.resurrections
import com.tracel.plugin.command.presenter.line.ChangeLinePresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.rollback.composer.FULL_FLUSH_SECONDS
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.time.Duration.Companion.milliseconds

/** How long planning may run before the player is told it has started. */
private const val PLANNING_NOTICE_MILLIS = 2_000L

/** Whose undo stack a job lands on: a player's own, or the console's. */
internal fun CommandSender.actor(): HolderId? = (this as? Player)?.let { HolderId.Player(it.uniqueId) }

/** Action responsible for orchestrating rollback operations, retries, and previews. */
// TODO: improve this in future
class RollbackAction internal constructor(
    private val services: TracelServices,
    private val highlights: Highlights? = null,
) {
    companion object {
        const val STALE_ATTEMPTS = 3
        const val GHOST_SECONDS = 10
    }

    /**
     * Orchestrates rollback operations, retries, and previews, and then
     * rolls back what needed.
     */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (services.composite.isRunning) {
            sender.send("common.busy")
            return
        }

        // TODO: add more guards

        if (parsed.errors.isNotEmpty()) {
            RollbackPresenter.refused(
                sender,
                parsed.errors.asReason(),
                tr("common.hint.fix_flags", "command" to "rollback")
            )
            return
        }

        if (parsed.structureOnly && parsed.materialOnly) {
            RollbackPresenter.refused(
                sender,
                tr("rollback.reason.opposites"),
                tr("common.hint.fix_flags", "command" to "rollback")
            )
            return
        }

        when (val filter = RollbackArgument.build(sender, parsed, limit = Int.MAX_VALUE)) {
            is FilterResult.Rejected -> {
                RollbackPresenter.refused(
                    sender,
                    filter.reasons.asReason(),
                    tr("common.hint.fix_flags", "command" to "rollback")
                )
            }

            is FilterResult.Ok -> if (!services.composite.claimGate()) {
                sender.send("common.busy")
            } else services.scope.launch {
                Telemetry.flags(Telemetry.ROLLBACK_FLAGS, parsed)
                if (!parsed.preview) Telemetry.rollbackReach(parsed)
                try {
                    services.purgeGate.awaitSlice()
                    services.composite.holdingStill(filter.filter.region) { rollbackFiltered(sender, parsed, filter) }
                } finally {
                    services.composite.releaseGate()
                }
            }
        }
    }

    private suspend fun rollbackFiltered(sender: CommandSender, parsed: ParsedLookupArgs, filter: FilterResult.Ok) {
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
                Telemetry.rollback("plan failed")
                RollbackPresenter.refused(
                    sender,
                    tr("rollback.reason.plan_failed", "reason" to unexpected(failure)),
                    tr("rollback.hint.again")
                )
                null
            }
        }

        val started = System.nanoTime()
        val notice = services.scope.launch {
            delay(PLANNING_NOTICE_MILLIS.milliseconds)
            sender.say(tr("rollback.planning"))
        }
        val planned = try {
            replan()
        } finally {
            notice.cancel()
        } ?: return
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        if (tookMillis >= PLANNING_NOTICE_MILLIS && !parsed.preview) {
            sender.say(
                tr(
                    "rollback.planned",
                    "count" to planned.composite.create.size + planned.composite.destroy.size,
                    "seconds" to "%.1f".format(tookMillis / 1000.0),
                ),
            )
        }
        val halves = halvesOf(parsed, filter.actions)
        if (planned.imported > 0) sender.say(info(tr("rollback.imported", "count" to planned.imported)))
        if (!planned.flushed) sender.say(info(tr("rollback.not_flushed", "seconds" to FULL_FLUSH_SECONDS)))
        if (parsed.preview) {
            val ghosts = if (sender is Player && highlights != null) {
                highlights.ghost(sender, planned.composite.create, planned.composite.destroy, GHOST_SECONDS)
            } else 0
            RollbackPresenter.preview(sender, planned, halves, ghosts, GHOST_SECONDS)
            services.scope.launch { warmForPreview(services, planned) }
        } else if (!askedAboutEntities(sender, planned, parsed.confirmed)) {
            runRollback(sender, planned, halves, parsed.strict, replan)
        }
    }

    private fun halvesOf(parsed: ParsedLookupArgs, actions: ActionFilter): Component {
        val structure = !parsed.materialOnly && actions.structural
        val material = !parsed.structureOnly && actions.material
        return tr(
            when {
                structure && material -> "rollback.halves.both"
                structure -> "rollback.halves.blocks"
                material -> "rollback.halves.items"
                else -> "rollback.halves.neither"
            }
        )
    }

    private suspend fun runRollback(
        sender: CommandSender,
        planned: Planned,
        halves: Component,
        strict: Boolean,
        replan: suspend () -> Planned?,
    ) {
        val started = System.nanoTime()
        var attempt = planned
        repeat(STALE_ATTEMPTS) {
            if (attempt.composite.isEmpty) {
                Telemetry.rollback("nothing to do")
                sender.send("rollback.nothing", "halves" to halves)
                return
            }
            val outcome = try {
                services.composite.apply(attempt.copy(by = sender.actor()), strict)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Telemetry.rollback("failed")
                RollbackPresenter.refused(sender, Component.text(unexpected(failure)), tr("rollback.hint.again"))
                return
            }
            when (outcome) {
                is RollbackResult.Done -> {
                    val took = (System.nanoTime() - started) / 1_000_000
                    Telemetry.rollbackDone(outcome, took)
                    if (RollbackPresenter.appliedNothing(outcome)) {
                        services.atomically {
                            services.jobs.markUndone(
                                outcome.job
                            )
                        }
                        services.rolledBack.restore(outcome.job.raw)
                    }
                    RollbackPresenter.report(sender, outcome, took)
                    return
                }

                is Unreachable -> {
                    Telemetry.rollback("unreachable")
                    RollbackPresenter.refused(
                        sender,
                        tr(
                            "rollback.reason.unreachable",
                            "holder" to ChangeLinePresenter.holder(outcome.holder),
                            "reason" to outcome.reason
                        ),
                        tr("rollback.hint.unreachable")
                    )
                    return
                }

                is Blocked -> {
                    Telemetry.rollback("blocked")
                    RollbackPresenter.refused(sender, tr("rollback.reason.blocked"), tr("rollback.hint.blocked"))
                    return
                }

                RollbackResult.Stale -> attempt = replan() ?: return
            }
        }
        Telemetry.rollback("stale")
        RollbackPresenter.refused(
            sender,
            tr("rollback.reason.stale", "attempts" to STALE_ATTEMPTS),
            tr("rollback.hint.stale")
        )
    }

    private fun askedAboutEntities(sender: CommandSender, planned: Planned, confirmed: Boolean): Boolean {
        if (confirmed) return false
        val spawns = planned.composite.create.resurrections()
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
