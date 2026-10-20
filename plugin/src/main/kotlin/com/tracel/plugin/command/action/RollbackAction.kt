package com.tracel.plugin.command.action

import com.tracel.api.item.ItemHolder
import com.tracel.api.result.Res
import com.tracel.api.rollback.ConflictPolicy
import com.tracel.api.rollback.RollbackError
import com.tracel.api.rollback.RollbackReady
import com.tracel.api.rollback.Rollbacks
import com.tracel.plugin.command.action.support.actor
import com.tracel.plugin.command.action.support.askedAboutEntities
import com.tracel.plugin.command.args.action.ActionFilter
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.rollback.FilterResult
import com.tracel.plugin.command.args.rollback.RollbackArgument
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.rollback.composer.FULL_FLUSH_SECONDS
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.time.Duration.Companion.milliseconds

/** How long planning may run before the player is told it has started. */
private const val PLANNING_NOTICE_MILLIS = 2_000L

/** Action responsible for orchestrating rollback operations, retries, and previews. */
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

        when (val filter = RollbackArgument.build(sender, parsed)) {
            is FilterResult.Rejected -> {
                RollbackPresenter.refused(
                    sender,
                    filter.reasons.asReason(),
                    tr("common.hint.fix_flags", "command" to "rollback")
                )
            }

            is FilterResult.Ok -> services.scope.launch {
                Telemetry.flags(Telemetry.ROLLBACK_FLAGS, parsed)
                if (!parsed.preview) Telemetry.rollbackReach(parsed)
                val rollbacks = services.commandApi.rollbacks(sender.actor(), parsed.confirmed)
                val held = rollbacks.hold(filter.query.area) { rollbackFiltered(sender, parsed, filter, rollbacks) }
                if (held is Res.Err) refuse(sender, held.error, planning = true)
            }
        }
    }

    private suspend fun rollbackFiltered(
        sender: CommandSender,
        parsed: ParsedLookupArgs,
        filter: FilterResult.Ok,
        rollbacks: Rollbacks,
    ) {
        val draft = rollbacks.rollback(filter.query)
            .onConflict(if (parsed.strict) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE)
            .allowMint()
        val replan: suspend () -> RollbackReady? = {
            when (val got = draft.preview()) {
                is Res.Ok -> got.value
                is Res.Err -> {
                    refuse(sender, got.error, planning = true)
                    null
                }
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
                    "count" to planned.blocks,
                    "seconds" to "%.1f".format(tookMillis / 1000.0),
                ),
            )
        }
        val halves = halvesOf(parsed, filter.actions)
        if (planned.imported > 0) sender.say(info(tr("rollback.imported", "count" to planned.imported)))
        if (!planned.complete) sender.say(info(tr("rollback.not_flushed", "seconds" to FULL_FLUSH_SECONDS)))
        if (parsed.preview) {
            val ghosts = if (sender is Player && highlights != null) {
                highlights.ghost(sender, planned.blockChanges, GHOST_SECONDS)
            } else 0
            RollbackPresenter.preview(sender, planned, halves, ghosts, GHOST_SECONDS)
            services.scope.launch { planned.warm() }
        } else {
            val asked = services.askedAboutEntities(sender, parsed.confirmed) { planned.entityTypes }
            if (!asked) runRollback(sender, planned, halves, replan)
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
        planned: RollbackReady,
        halves: Component,
        replan: suspend () -> RollbackReady?,
    ) {
        val started = System.nanoTime()
        var attempt = planned
        repeat(STALE_ATTEMPTS) {
            if (attempt.isEmpty) {
                Telemetry.rollback("nothing to do")
                sender.send("rollback.nothing", "halves" to halves)
                return
            }
            when (val outcome = attempt.apply()) {
                is Res.Ok -> {
                    val took = (System.nanoTime() - started) / 1_000_000
                    Telemetry.rollbackDone(outcome.value, took)
                    RollbackPresenter.report(sender, outcome.value, took)
                    return
                }

                is Res.Err -> {
                    val error = outcome.error
                    if (error !is RollbackError.Stale) return refuse(sender, error, planning = false)
                    attempt = error.replan
                }
            }
        }
        Telemetry.rollback("stale")
        RollbackPresenter.refused(
            sender,
            tr("rollback.reason.stale", "attempts" to STALE_ATTEMPTS),
            tr("rollback.hint.stale")
        )
    }

    private fun refuse(sender: CommandSender, error: RollbackError, planning: Boolean) {
        when (error) {
            RollbackError.Busy -> sender.send("common.busy")

            is RollbackError.Unreachable -> unreachable(sender, error.holder, error.reason)
            is RollbackError.AddonMissing -> unreachable(sender, error.holder, error.reason)

            is RollbackError.Blocked, is RollbackError.Borrowed -> {
                Telemetry.rollback("blocked")
                RollbackPresenter.refused(sender, tr("rollback.reason.blocked"), tr("rollback.hint.blocked"))
            }

            is RollbackError.Failed -> failed(sender, error.message, planning)
            is RollbackError.Unsupported -> failed(sender, error.reason, planning)
            else -> failed(sender, error.toString(), planning)
        }
    }

    private fun unreachable(sender: CommandSender, holder: ItemHolder, reason: String) {
        Telemetry.rollback("unreachable")
        RollbackPresenter.refused(
            sender,
            RollbackPresenter.unreachable(holder, reason),
            tr("rollback.hint.unreachable"),
        )
    }

    private fun failed(sender: CommandSender, message: String, planning: Boolean) {
        val reason = said(message)
        if (planning) {
            Telemetry.rollback("plan failed")
            RollbackPresenter.refused(
                sender,
                tr("rollback.reason.plan_failed", "reason" to reason),
                tr("rollback.hint.again")
            )
        } else {
            Telemetry.rollback("failed")
            RollbackPresenter.refused(sender, Component.text(reason), tr("rollback.hint.again"))
        }
    }
}
