package com.tracel.plugin.command.presenter

import com.tracel.plugin.actor.ActorHolders
import com.tracel.api.item.ItemHolder
import com.tracel.api.rollback.RollbackApplied
import com.tracel.api.rollback.RollbackReady
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.world.NamespacedName
import com.tracel.plugin.command.presenter.line.ChangeLinePresenter
import com.tracel.plugin.i18n.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import java.util.*

object RollbackPresenter {
    private const val INSTANT_MILLIS = 300L

    /** `/tracel rollback` usage. */
    fun usage(sender: CommandSender) = sender.usage("rollback")

    /** A rollback that did not start or did not finish, told like every other failure: why, and what to do. */
    fun refused(sender: CommandSender, reason: Component, hint: Component) =
        sender.failed("rollback.failed", reason, hint)

    /** Why a rollback or an undo stopped at a holder it could not reach. */
    fun unreachable(holder: ItemHolder, reason: String): Component = tr(
        "rollback.reason.unreachable",
        "holder" to ChangeLinePresenter.holder(ActorHolders.holderId(holder)),
        "reason" to reason,
    )

    /** A preview of what a rollback would do, with a link to apply it. */
    fun preview(sender: CommandSender, ready: RollbackReady, halves: Component, ghosts: Int, ghostSeconds: Int) {
        if (ready.isEmpty) {
            sender.send("rollback.nothing", "halves" to halves)
            return
        }
        val lines = buildList {
            add(tr("rollback.preview.title"))
            add(Component.empty())
            if (ghosts > 0) add(info(tr("rollback.preview.ghost", "count" to ghosts, "seconds" to ghostSeconds)))
            add(tr("rollback.preview.would", "summary" to summary(ready).joined()))
        }
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    /**
     * A rollback that finished, told like every other success: what came back, what was removed, and
     * how long it took.
     */
    fun report(sender: CommandSender, done: RollbackApplied, tookMillis: Long) {
        val counts = counts(done)
        val undo = tr("rollback.button.undo")
            .clickEvent(ClickEvent.runCommand("/tracel rollback undo ${done.job.raw}"))
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
    fun reportUndo(sender: CommandSender, done: RollbackUndone, tookMillis: Long) {
        val lines = buildList {
            add(tr("undo.done.title"))
            add(Component.empty())
            add(tr("undo.done.result", "changes" to done.changed))
            add(tr("common.label.time", "time" to took(tookMillis)))
        }
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    /** A rollback that did not start or did not finish, told like every other failure: why, and what to do. */
    fun refusedUndo(sender: CommandSender, reason: Component, hint: Component) =
        sender.failed("undo.failed", reason, hint)

    /** What [ready] would do, in words. */
    fun summary(ready: RollbackReady): List<Component> = counts(ready).all()

    /** @return a component describing the most common type of resurrection in [spawns], counted by type. */
    fun mostly(spawns: Map<NamespacedName, Int>): Component {
        val counted = spawns.entries.groupingBy { it.key.value }.fold(0) { sum, entry -> sum + entry.value }
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
        val compensated: Int,
        val outside: Int,
    ) {
        fun isEmpty() = restored + removed + reclaimed + uncrafted + compensated + outside == 0
        fun world() = listOf("restored" to restored, "removed" to removed).tally()
        fun items() = listOf("returned" to reclaimed + compensated, "uncrafted" to uncrafted).tally()
        fun all() = world() + items() + listOf("outside" to outside).tally()

        private fun List<Pair<String, Int>>.tally(): List<Component> =
            filter { it.second > 0 }.map { (key, count) -> tr("rollback.summary.$key", "count" to count) }
    }

    private fun counts(done: RollbackApplied): Counts = Counts(
        done.restored, done.removed, done.balance.taken, done.balance.unmade, done.balance.minted, done.outside,
    )

    private fun counts(ready: RollbackReady): Counts = Counts(
        ready.restores, ready.removals, ready.balance.taken, ready.balance.unmade, ready.balance.minted, ready.outside,
    )
}
