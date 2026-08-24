package com.tracel.plugin.command

import com.tracel.engine.rollback.RollbackOutcome
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.involution.InvolutionPlanner
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
 * `/tracel rollback preview|apply|undo`.
 */
class RollbackCommand(private val services: TracelServices) : BasicCommand {
    private val usage = listOf(
        "Usage: /tracel rollback preview <lotId>",
        "       /tracel rollback apply <lotId> [player]",
        "       /tracel rollback undo <jobId>",
    )

    override fun execute(source: CommandSourceStack, args: Array<String>) {
        when (args.getOrNull(1)) {
            "preview" -> {
                val lotId = args.getOrNull(2)?.toLongOrNull() ?: return usage(source)
                inBackground { preview(source, lotId) }
            }
            "apply" -> {
                val lotId = args.getOrNull(2)?.toLongOrNull() ?: return usage(source)
                inBackground { apply(source, lotId, args.getOrNull(3)) }
            }
            "undo" -> {
                val jobId = args.getOrNull(2)?.toLongOrNull() ?: return usage(source)
                inBackground { undo(source, jobId) }
            }
            else -> usage(source)
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
        if (args.size != 2) return emptyList()
        return listOf("preview", "apply", "undo").filter { it.startsWith(args[1]) }
    }

    override fun permission(): String = "tracel.rollback"

    private fun usage(source: CommandSourceStack) = usage.forEach(source.sender::sendMessage)

    // Must not be one unit of work
    private fun inBackground(block: suspend () -> Unit) {
        services.scope.launch { block() }
    }

    private suspend fun preview(source: CommandSourceStack, lotId: Long) {
        val outcome = runCatching {
            val worldQuery = WorldQuery { Bukkit.getPlayer(it) != null }
            RollbackPlanner(services.repo, worldQuery).plan(listOf(LotId(lotId)))
        }

        val plan = outcome.getOrNull()
        if (plan == null) {
            source.sender.sendMessage("Could not plan a rollback for lot $lotId: ${outcome.exceptionOrNull()?.message}")
            return
        }

        source.sender.sendMessage(
            "Rollback plan for lot $lotId: ${plan.takeCount} take(s), ${plan.mintCount} mint(s), ${plan.unmakeCount} unmake(s)"
        )
        plan.steps.forEachIndexed { index, step -> source.sender.sendMessage(" ${index + 1}. $step") }
    }

    // TODO: rewrite ts
    private suspend fun apply(source: CommandSourceStack, lotId: Long, playerName: String?) {
        val restoreTo = withContext(services.schedulers.global) { resolveRestoreTo(source, playerName) }
        if (restoreTo == null) {
            source.sender.sendMessage("Unknown player '$playerName' — name an online or previously-seen player explicitly")
            return
        }

        val planOutcome = runCatching {
            val worldQuery = WorldQuery { Bukkit.getPlayer(it) != null }
            RollbackPlanner(services.repo, worldQuery).plan(listOf(LotId(lotId)))
        }
        val plan = planOutcome.getOrNull()
        if (plan == null) {
            source.sender.sendMessage("Could not plan a rollback for lot $lotId: ${planOutcome.exceptionOrNull()?.message}")
            return
        }

        val preflight = services.restorer.preflight(plan, restoreTo)
        if (preflight is PreflightResult.Unreachable) {
            source.sender.sendMessage("Cannot physically apply: ${preflight.holder} — ${preflight.reason}")
            return
        }

        val outcome = runCatching {
            val job = services.counters.nextRollbackJobId()
            job to services.rollback.run(job, listOf(LotId(lotId)), restoreTo)
        }
        val result = outcome.getOrNull()
        if (result == null) {
            source.sender.sendMessage("Could not apply a rollback for lot $lotId: ${outcome.exceptionOrNull()?.message}")
            return
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

    private suspend fun undo(source: CommandSourceStack, jobId: Long) {
        val job = RollbackJobId(jobId)

        val record = services.jobs.find(job)
        if (record == null) {
            source.sender.sendMessage("No rollback job $jobId is on record to undo")
            return
        }

        val steps = InvolutionPlanner(services.repo).plan(record)

        val preflight = services.restorer.undoPreflight(steps)
        if (preflight is PreflightResult.Unreachable) {
            source.sender.sendMessage("Cannot physically undo: ${preflight.holder} — ${preflight.reason}")
            return
        }

        val outcome = runCatching { services.undo.undo(job) }
        val result = outcome.getOrNull()
        if (result == null) {
            source.sender.sendMessage("Could not undo rollback job $jobId: ${outcome.exceptionOrNull()?.message}")
            return
        }

        when (result) {
            is InvolutionOutcome.Undone -> {
                val report = services.restorer.undoRestore(result.steps, job)
                source.sender.sendMessage("Rollback job $jobId undone.")
                for ((holder, note) in report.queued) source.sender.sendMessage(" $holder: $note")
                if (!report.fullyRestored) {
                    source.sender.sendMessage("Ledger updated, but physical restoration was incomplete:")
                    for ((holder, reason) in report.failures) source.sender.sendMessage(" $holder: $reason")
                }
            }

            is InvolutionOutcome.AlreadyUndone ->
                source.sender.sendMessage("Rollback job $jobId was already undone — not touching the world again.")
            is InvolutionOutcome.Blocked ->
                source.sender.sendMessage("Blocked: lots already leased to another job — ${result.conflicts}")
            InvolutionOutcome.NotFound ->
                source.sender.sendMessage("No rollback job $jobId is on record to undo")
        }
    }

    private fun resolveRestoreTo(source: CommandSourceStack, playerName: String?): HolderId.Player? {
        if (playerName == null) return (source.sender as? Player)?.let { HolderId.Player(it.uniqueId) }

        Bukkit.getPlayer(playerName)?.let { return HolderId.Player(it.uniqueId) }

        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(playerName)
        return if (offline.hasPlayedBefore()) HolderId.Player(offline.uniqueId) else null
    }
}
