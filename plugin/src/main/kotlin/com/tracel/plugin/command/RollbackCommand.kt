package com.tracel.plugin.command

import com.tracel.engine.rollback.InvolutionOutcome
import com.tracel.engine.rollback.InvolutionPlanner
import com.tracel.engine.rollback.RollbackOutcome
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.PreflightResult
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Rollback command.
 *
 * - `/tracel rollback preview <lotId>` — plans a rollback and reports it, without applying it.
 * - `/tracel rollback apply <lotId> [playerName]` — actually runs it, via [TracelServices.rollback].
 * - `/tracel rollback undo <jobId>` — reverses an already-applied job, via [TracelServices.undo].
 */
// TODO: should be rewritten in future
class RollbackCommand(private val services: TracelServices) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<String>) {
        if (args.getOrNull(0) != "rollback") {
            source.sender.sendMessage("Usage: /tracel rollback <preview|apply|undo> <lotId|jobId> ...")
            return
        }

        when (args.getOrNull(1)) {
            "preview" -> preview(source, args)
            "apply" -> apply(source, args)
            "undo" -> undo(source, args)
            else -> source.sender.sendMessage("Usage: /tracel rollback <preview|apply|undo> <lotId|jobId> ...")
        }
    }

    private fun preview(source: CommandSourceStack, args: Array<String>) {
        val lotId = args.getOrNull(2)?.toLongOrNull()
        if (lotId == null) {
            source.sender.sendMessage("Usage: /tracel rollback preview <lotId>")
            return
        }

        services.scope.launch {
            val outcome = runCatching {
                withContext(services.schedulers.storage) {
                    val worldQuery = WorldQuery { Bukkit.getPlayer(it) != null }
                    RollbackPlanner(services.repo, worldQuery).plan(listOf(LotId(lotId)))
                }
            }

            val plan = outcome.getOrNull()
            if (plan == null) {
                source.sender.sendMessage("Could not plan a rollback for lot $lotId: ${outcome.exceptionOrNull()?.message}")
                return@launch
            }

            source.sender.sendMessage(
                "Rollback plan for lot $lotId: ${plan.takeCount} take(s), ${plan.mintCount} mint(s), ${plan.unmakeCount} unmake(s)"
            )
            plan.steps.forEachIndexed { index, step -> source.sender.sendMessage(" ${index + 1}. $step") }
        }
    }

    private fun apply(source: CommandSourceStack, args: Array<String>) {
        val lotId = args.getOrNull(2)?.toLongOrNull()
        if (lotId == null) {
            source.sender.sendMessage("Usage: /tracel rollback apply <lotId> [playerName]")
            return
        }
        val playerName = args.getOrNull(3)

        services.scope.launch {
            val restoreTo = withContext(services.schedulers.global) { resolveRestoreTo(source, playerName) }
            if (restoreTo == null) {
                source.sender.sendMessage("Unknown player '$playerName' — name an online or previously-seen player explicitly")
                return@launch
            }

            // Plan first, outside the ledger-mutating call, so an Unmake step or an unreachable
            // physical holder can be refused before RollbackJobCoordinator.run ever touches the
            // ledger — there is no way to "undo" a physical inventory mutation, so the ledger
            // must not move until physical feasibility is already known.
            val planOutcome = runCatching {
                withContext(services.schedulers.storage) {
                    val worldQuery = WorldQuery { Bukkit.getPlayer(it) != null }
                    RollbackPlanner(services.repo, worldQuery).plan(listOf(LotId(lotId)))
                }
            }
            val plan = planOutcome.getOrNull()
            if (plan == null) {
                source.sender.sendMessage("Could not plan a rollback for lot $lotId: ${planOutcome.exceptionOrNull()?.message}")
                return@launch
            }

            val preflight = services.restorer.preflight(plan, restoreTo)
            if (preflight is PreflightResult.Unreachable) {
                source.sender.sendMessage("Cannot physically apply: ${preflight.holder} — ${preflight.reason}")
                return@launch
            }

            val outcome = runCatching {
                withContext(services.schedulers.storage) {
                    val job = services.counters.nextRollbackJobId()
                    job to services.rollback.run(job, listOf(LotId(lotId)), restoreTo)
                }
            }
            val result = outcome.getOrNull()
            if (result == null) {
                source.sender.sendMessage("Could not apply a rollback for lot $lotId: ${outcome.exceptionOrNull()?.message}")
                return@launch
            }

            val (job, rollbackOutcome) = result
            when (rollbackOutcome) {
                is RollbackOutcome.Applied -> {
                    val report = services.restorer.restore(plan, restoreTo, job)
                    source.sender.sendMessage(
                        "Rollback job ${job.raw} applied for lot $lotId: ${plan.takeCount} take(s), " +
                            "${plan.mintCount} mint(s), restored to $restoreTo"
                    )
                    for ((holder, note) in report.queued) source.sender.sendMessage(" $holder: $note")
                    if (!report.fullyRestored) {
                        source.sender.sendMessage("Ledger updated, but physical restoration was incomplete:")
                        for ((holder, reason) in report.failures) source.sender.sendMessage(" $holder: $reason")
                    }
                }
                is RollbackOutcome.Blocked ->
                    source.sender.sendMessage("Blocked: lots already leased to another job — ${rollbackOutcome.conflicts}")
                is RollbackOutcome.Stale ->
                    source.sender.sendMessage("The world changed since this was last planned — run /tracel rollback preview $lotId again")
            }
        }
    }

    private fun undo(source: CommandSourceStack, args: Array<String>) {
        val jobIdRaw = args.getOrNull(2)?.toLongOrNull()
        if (jobIdRaw == null) {
            source.sender.sendMessage("Usage: /tracel rollback undo <jobId>")
            return
        }
        val job = RollbackJobId(jobIdRaw)

        services.scope.launch {
            val record = withContext(services.schedulers.storage) { services.jobs.find(job) }
            if (record == null) {
                source.sender.sendMessage("No rollback job $jobIdRaw is on record to undo")
                return@launch
            }

            // Same discipline as apply(): plan first, refuse what physical restoration can't
            // handle, and only then let InvolutionJobCoordinator touch the ledger — there is no
            // way to "undo" a physical inventory mutation either, once made.
            val steps = withContext(services.schedulers.storage) { InvolutionPlanner(services.repo).plan(record) }

            val preflight = services.restorer.undoPreflight(steps)
            if (preflight is PreflightResult.Unreachable) {
                source.sender.sendMessage("Cannot physically undo: ${preflight.holder} — ${preflight.reason}")
                return@launch
            }

            val outcome = runCatching {
                withContext(services.schedulers.storage) { services.undo.undo(job) }
            }
            val result = outcome.getOrNull()
            if (result == null) {
                source.sender.sendMessage("Could not undo rollback job $jobIdRaw: ${outcome.exceptionOrNull()?.message}")
                return@launch
            }

            when (result) {
                is InvolutionOutcome.Undone -> {
                    val report = services.restorer.undoRestore(result.steps, job)
                    source.sender.sendMessage("Rollback job $jobIdRaw undone.")
                    for ((holder, note) in report.queued) source.sender.sendMessage(" $holder: $note")
                    if (!report.fullyRestored) {
                        source.sender.sendMessage("Ledger updated, but physical restoration was incomplete:")
                        for ((holder, reason) in report.failures) source.sender.sendMessage(" $holder: $reason")
                    }
                }
                // Deliberately does not call undoRestore again here — physical restoration has no
                // memory of its own the way the ledger-side journal does, so re-running it for a
                // job already finished by a previous call would re-apply whatever side of it
                // previously succeeded, duplicating material.
                is InvolutionOutcome.AlreadyUndone ->
                    source.sender.sendMessage("Rollback job $jobIdRaw was already undone — not touching the world again.")
                is InvolutionOutcome.Blocked ->
                    source.sender.sendMessage("Blocked: lots already leased to another job — ${result.conflicts}")
                InvolutionOutcome.NotFound ->
                    source.sender.sendMessage("No rollback job $jobIdRaw is on record to undo")
            }
        }
    }

    /**
     * Resolves [playerName] to a [HolderId.Player] regardless of whether they are online right
     * now — an offline target is not an error, [PhysicalRestorer][com.tracel.plugin.rollback.PhysicalRestorer]
     * durably queues their material for delivery on next login instead of failing.
     *
     * `null` only when no player named [playerName] has ever actually played on this server,
     * which really is a mistyped name and isn't a legitimate offline target.
     *
     * With no [playerName] at all, defaults to the command's own sender, who by definition is
     * online right now.
     */
    private fun resolveRestoreTo(source: CommandSourceStack, playerName: String?): HolderId.Player? {
        if (playerName == null) return (source.sender as? Player)?.let { HolderId.Player(it.uniqueId) }

        Bukkit.getPlayer(playerName)?.let { return HolderId.Player(it.uniqueId) }

        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(playerName)
        return if (offline.hasPlayedBefore()) HolderId.Player(offline.uniqueId) else null
    }

    override fun permission(): String = "tracel.rollback"
}
