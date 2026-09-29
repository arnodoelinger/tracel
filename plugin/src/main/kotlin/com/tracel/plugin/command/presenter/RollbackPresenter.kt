package com.tracel.plugin.command.presenter

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.dialog.renderStructureStep
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.report.StructureReport
import org.bukkit.command.CommandSender

// TODO: rewrite
object RollbackPresenter {
    private const val PREVIEW_LINES = 10
    private const val LISTED_JOBS = 5

    private const val NOTHING = "nothing, the world already matches"

    val USAGE: List<String> = listOf(
        "Usage: /tracel rollback t:<time> s:<Nb|Nc|block|chunk> [u:<player>] [i:<item>] [#blocks|#items|#explosion] [#preview]",
        "t: and s: are required. Prefixes are optional: /tracel rollback 10m 20b Steve"
    )

    const val UNDO_USAGE: String = "Usage: /tracel restore"

    fun usage(sender: CommandSender) {
        USAGE.forEach(sender::sendMessage)
    }

    fun undoUsage(sender: CommandSender) {
        sender.sendMessage(UNDO_USAGE)
    }

    fun preview(sender: CommandSender, planned: Planned, halves: String, entityRestoreLimit: Int) {
        val composite = planned.composite
        if (composite.isEmpty) {
            sender.sendMessage("Rollback: nothing matches (looked at $halves).")
            reportPlaced(sender, planned)
            return
        }
        warnIfShortWindow(sender, planned)
        sender.sendMessage("Would ${summary(planned)}.")
        val spawns = composite.create.resurrections()
        if (spawns.size > entityRestoreLimit) {
            sender.sendMessage(
                "  ${spawns.size} of those are entities (${mostly(spawns)}) — past the " +
                        "$entityRestoreLimit allowed in one go, so the real run needs #confirm."
            )
        }
        composite.create.take(PREVIEW_LINES).forEach { sender.sendMessage("  restore ${renderStructureStep(it)}") }
        composite.material.steps.take(PREVIEW_LINES).forEach { sender.sendMessage("  $it") }
        composite.destroy.take(PREVIEW_LINES).forEach { sender.sendMessage("  remove ${renderStructureStep(it)}") }
        val shown = minOf(composite.create.size, PREVIEW_LINES) +
                minOf(composite.material.steps.size, PREVIEW_LINES) +
                minOf(composite.destroy.size, PREVIEW_LINES)
        val total = composite.create.size + composite.material.steps.size + composite.destroy.size
        if (total > shown) sender.sendMessage("  ... and ${total - shown} more")
    }

    fun report(sender: CommandSender, done: RollbackResult.Done) {
        if (appliedNothing(done)) sender.sendMessage("Rolled back: $NOTHING.")
        else sender.sendMessage("Rolled back: ${summary(done)}. /tracel restore to take it back.")
        warnIfShortWindow(sender, done.plan)

        val minted = done.plan.composite.material.mintCount
        if (minted > 0) sender.sendMessage("  $minted item(s) were compensated rather than recovered")
        reportPlaced(sender, done.plan)

        reportProblems(sender, done.material.queued, done.material.failures, done.structure, done.material.spilled)
    }

    fun reportPlaced(sender: CommandSender, planned: Planned) {
        val stuck = planned.placedAndUnreachable
        if (stuck == 0) return
        sender.sendMessage(
            "  $stuck stack(s) have been built into the world since, and only a rollback that " +
                    "removes blocks can get them back — run it again without a: / #items."
        )
    }

    fun warnIfShortWindow(sender: CommandSender, planned: Planned) {
        if (planned.flushed) return
        sender.sendMessage(
            "  Warning: not everything captured had finished being written — this window may be " +
                    "missing the last moment of the damage. Run it again to catch the rest."
        )
    }

    fun reportProblems(
        sender: CommandSender,
        queued: Map<HolderId, String>,
        failures: Map<HolderId, String>,
        structure: StructureReport,
        spilled: Int = 0,
    ) {
        if (spilled > 0) {
            sender.sendMessage("  $spilled stack(s) did not fit and are on the ground where they were sent.")
        }
        if (queued.isNotEmpty()) {
            sender.sendMessage("  ${queued.size} offline player(s) will get their material on next login.")
        }
        if (failures.isNotEmpty()) {
            val first = failures.entries.first()
            val more = if (failures.size > 1) " (and ${failures.size - 1} more)" else ""
            sender.sendMessage("  Ledger updated, but ${first.key} could not be reached: ${first.value}$more")
        }
        if (structure.overwritten > 0) {
            sender.sendMessage("  ${structure.overwritten} block(s) were not exactly as logged (grass, water, snow) and were put back anyway.")
        }
        if (structure.skipped.isNotEmpty()) {
            val commonest = structure.skipped.groupingBy { it.reason }.eachCount().maxByOrNull { it.value }
            sender.sendMessage("  ${structure.skipped.size} not restored — mostly: ${commonest?.key}")
        }
    }

    fun leaseConflicts(conflicts: Map<LotId, RollbackJobId>): String {
        val jobs = conflicts.values.distinct()
        val lots = conflicts.size
        return when (jobs.size) {
            0 -> "those lots are leased to another job."
            1 -> "$lots lot(s) are leased to job ${jobs.single().raw}, which is still running. Try again once it finishes."
            else -> "$lots lot(s) are leased to ${jobs.size} other job(s): " +
                    jobs.take(LISTED_JOBS).joinToString(", ") { it.raw.toString() } +
                    (if (jobs.size > LISTED_JOBS) ", ..." else "")
        }
    }

    /** Whether [done] changed nothing at all: no block, no entity, no item. */
    fun appliedNothing(done: RollbackResult.Done): Boolean = summary(done) == NOTHING

    /**
     * What the rollback actually did. Counted from the plan, a second run of the same rollback over a world
     * that already matched said it restored everything again.
     */
    fun summary(done: RollbackResult.Done): String {
        val applied = done.structure.applied
        val removed =
            applied.count { it is StructureStep.RemoveEntity || (it is StructureStep.SetBlock && it.target.isAirLike) }
        val restored = applied.size - removed
        val material = done.plan.composite.material
        val parts = buildList {
            if (restored > 0) add("$restored restored")
            if (removed > 0) add("$removed removed")
            if (material.takeCount > 0) add("${material.takeCount} reclaimed")
            if (material.unmakeCount > 0) add("${material.unmakeCount} uncrafted")
            if (material.mintCount > 0) add("${material.mintCount} compensated")
        }
        return if (parts.isEmpty()) NOTHING else parts.joinToString(", ")
    }

    fun summary(planned: Planned): String {
        val composite = planned.composite
        val material = composite.material
        val parts = buildList {
            if (composite.create.isNotEmpty()) add("${composite.create.size} restored")
            if (composite.destroy.isNotEmpty()) add("${composite.destroy.size} removed")
            if (material.takeCount > 0) add("${material.takeCount} reclaimed")
            if (material.unmakeCount > 0) add("${material.unmakeCount} uncrafted")
            if (material.mintCount > 0) add("${material.mintCount} compensated")
        }
        return if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
    }

    fun List<StructureStep>.resurrections(): List<StructureStep.SpawnEntity> =
        filterIsInstance<StructureStep.SpawnEntity>().filter { it.expected == null }

    fun mostly(spawns: List<StructureStep.SpawnEntity>): String {
        val counted = spawns.groupingBy { it.shape.type.value.substringAfter(':') }.eachCount()
        val (type, count) = counted.maxByOrNull { it.value } ?: return "mixed"
        return if (counted.size == 1) "all $type" else "mostly $type — $count of them"
    }
}
