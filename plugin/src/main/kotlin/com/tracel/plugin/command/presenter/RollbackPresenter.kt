package com.tracel.plugin.command.presenter

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.plugin.i18n.*
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import java.util.*

object RollbackPresenter {
    private const val INSTANT_MILLIS = 200L

    /** `/tracel rollback` usage. */
    fun usage(sender: CommandSender) = sender.usage("rollback")

    /** A rollback that did not start or did not finish, told like every other failure: why, and what to do. */
    fun refused(sender: CommandSender, reason: Component, hint: Component) =
        sender.failed("rollback.failed", reason, hint)

    /** A preview of what a rollback would do, with a link to apply it. */
    fun preview(sender: CommandSender, planned: Planned, halves: Component, ghosts: Int, ghostSeconds: Int) {
        val composite = planned.composite
        if (composite.isEmpty) {
            sender.send("rollback.nothing", "halves" to halves)
            return
        }
        val lines = buildList {
            add(tr("rollback.preview.title"))
            add(Component.empty())
            if (ghosts > 0) add(info(tr("rollback.preview.ghost", "count" to ghosts, "seconds" to ghostSeconds)))
            add(tr("rollback.preview.would", "summary" to summary(planned).joined()))
        }
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    /**
     * A rollback that finished, told like every other success: what came back, what was removed, and
     * how long it took.
     */
    fun report(sender: CommandSender, done: RollbackResult.Done, tookMillis: Long) {
        val counts = counts(done)
        val undo = tr("rollback.button.undo")
            .clickEvent(ClickEvent.runCommand("/tracel undo ${done.job.raw}"))
            .hoverEvent(HoverEvent.showText(tr("rollback.button.undo_hover")))
        val lines = buildList {
            add(tr("rollback.done.title"))
            add(Component.empty())
            add(
                if (counts.isEmpty()) tr("rollback.done.nothing")
                else tr("rollback.done.result", "parts" to counts.all().joined()),
            )
            if (!counts.isEmpty()) {
                add(tr("common.label.time", "time" to took(tookMillis)))
                add(Component.empty())
                add(undo)
            }
        }
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    /** The undo, told like a rollback: what came back and how long it took. */
    fun reportUndo(sender: CommandSender, done: UndoResult.Done, tookMillis: Long) {
        val lines = buildList {
            add(tr("undo.done.title"))
            add(Component.empty())
            add(tr("undo.done.result", "changes" to done.structure.count))
            add(tr("common.label.time", "time" to took(tookMillis)))
        }
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    /** A rollback that did not start or did not finish, told like every other failure: why, and what to do. */
    fun refusedUndo(sender: CommandSender, reason: Component, hint: Component) =
        sender.failed("undo.failed", reason, hint)

    /** Whether [done] changed nothing at all: no block, no entity, no item. */
    fun appliedNothing(done: RollbackResult.Done): Boolean = counts(done).isEmpty()

    /** Whether [planned] would change nothing at all: no block, no entity, no item. */
    fun summary(planned: Planned): List<Component> = counts(planned).all()

    /** @return the list of resurrection steps from a list of structure steps. */
    fun List<StructureStep>.resurrections(): List<StructureStep.SpawnEntity> =
        filterIsInstance<StructureStep.SpawnEntity>().filter { it.expected == null }

    /** @return a component describing the most common type of resurrection in [spawns]. */
    fun mostly(spawns: List<StructureStep.SpawnEntity>): Component {
        val counted = spawns.groupingBy { it.shape.type.value.substringAfter(':') }.eachCount()
        val (type, count) = counted.maxByOrNull { it.value } ?: return tr("rollback.mostly.mixed")
        return if (counted.size == 1) tr("rollback.mostly.all", "type" to type)
        else tr("rollback.mostly.most", "type" to type, "count" to count)
    }

    private fun took(millis: Long): Component =
        if (millis < INSTANT_MILLIS) tr("rollback.took.instant")
        else Component.text("%.1fs".format(Locale.ROOT, millis / 1_000.0))

    private fun List<Component>.joined(): Component = Component.join(
        JoinConfiguration.builder()
            .separator(Component.text(", "))
            .lastSeparator(tr("rollback.join.and"))
            .lastSeparatorIfSerial(tr("rollback.join.serial_and"))
            .build(),
        this,
    )

    private data class Counts(
        val restored: Int,
        val removed: Int,
        val reclaimed: Int,
        val uncrafted: Int,
        val compensated: Int
    ) {
        fun isEmpty() = restored + removed + reclaimed + uncrafted + compensated == 0
        fun world() = listOf("restored" to restored, "removed" to removed).tally()
        fun items() = listOf("returned" to reclaimed + compensated, "uncrafted" to uncrafted).tally()
        fun all() = world() + items()

        private fun List<Pair<String, Int>>.tally(): List<Component> =
            filter { it.second > 0 }.map { (key, count) -> tr("rollback.summary.$key", "count" to count) }
    }

    private fun counts(done: RollbackResult.Done): Counts {
        val applied = done.structure.applied
        val removed =
            applied.count { it is StructureStep.RemoveEntity || (it is StructureStep.SetBlock && it.target.isAirLike) }
        val material = done.plan.composite.material
        return Counts(applied.size - removed, removed, material.takeCount, material.unmakeCount, material.mintCount)
    }

    private fun counts(planned: Planned): Counts {
        val composite = planned.composite
        val material = composite.material
        return Counts(
            composite.create.size,
            composite.destroy.size,
            material.takeCount,
            material.unmakeCount,
            material.mintCount
        )
    }
}
