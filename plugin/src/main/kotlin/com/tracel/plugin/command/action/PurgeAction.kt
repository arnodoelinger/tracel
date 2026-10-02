package com.tracel.plugin.command.action

import com.tracel.model.id.WorldId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.command.action.ExportAction.Companion.records
import com.tracel.plugin.command.args.PurgeArgs
import com.tracel.plugin.command.args.PurgeArgument
import com.tracel.plugin.i18n.asReason
import com.tracel.plugin.i18n.confirm
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.joined
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.unexpected
import com.tracel.plugin.i18n.usage
import com.tracel.plugin.util.resolvePlayerUuid
import com.tracel.storage.ports.ops.PurgeCategory
import com.tracel.storage.ports.ops.PurgeFilter
import com.tracel.storage.ports.ops.PurgeReport
import com.tracel.storage.ports.ops.PurgeSpec
import com.tracel.storage.ports.ops.PurgeSummary
import com.tracel.storage.ports.ops.previewPurge
import com.tracel.storage.ports.ops.purgeAll
import com.tracel.storage.ports.ops.purgeSome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * `/tracel purge`: takes history out of the database, all of it or only what the filters name.
 * Without `#confirm` it takes nothing: it previews, then `#continue` asks for the last word.
 */
class PurgeAction(private val services: TracelServices) {
    /** Runs `/tracel purge` with the words after it. */
    fun execute(sender: CommandSender, tokens: List<String>) {
        val args = PurgeArgument.parse(tokens)
        if (args.errors.isNotEmpty()) return refuse(sender, args.errors.asReason())
        if (args.isEmpty) return sender.usage("purge")
        val command = "/tracel purge " + tokens.filterNot { it.isStep() }.joinToString(" ")
        if (args.everything) return everything(sender, args, command)

        val spec = specOf(sender, args) ?: return
        when {
            args.confirmed -> purge(sender, spec)
            args.continued -> sender.confirm()
            else -> preview(sender, args, spec, command)
        }
    }

    private fun refuse(sender: CommandSender, reason: Component) =
        sender.failed("purge.failed", reason, tr("common.hint.fix_flags", "command" to "purge"))

    private fun specOf(sender: CommandSender, args: PurgeArgs): PurgeSpec? {
        val problems = ArrayList<Component>()
        val world = args.world?.let { name ->
            val found = Bukkit.getWorlds().firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (found == null) problems += tr("common.reason.unknown_world", "name" to name)
            found?.let { WorldId(it.uid) }
        }
        val player = args.player?.let { name ->
            val found = resolvePlayerUuid(name)
            if (found == null) problems += tr("common.reason.unknown_player", "name" to name)
            found
        }
        val categories = args.categories.ifEmpty {
            if (player != null) PurgeCategory.entries.toSet() - PurgeCategory.CONTAINERS else PurgeCategory.entries.toSet()
        }
        if (PurgeCategory.CONTAINERS in categories && args.player != null) {
            problems += tr("purge.reason.containers_player")
        }
        if (problems.isNotEmpty()) {
            refuse(sender, problems.asReason())
            return null
        }
        val before = args.olderMillis?.let { System.currentTimeMillis() - it }
        return PurgeSpec(categories, PurgeFilter(before, world, player))
    }

    private fun String.isStep() = equals(PurgeArgument.CONFIRM, true) || equals(PurgeArgument.CONTINUE, true)

    private fun preview(sender: CommandSender, args: PurgeArgs, spec: PurgeSpec, command: String) {
        sender.send("purge.counting")
        services.scope.launch {
            try {
                services.flushCapture()
                val report = previewPurge(services.storage, spec)
                sender.say(previewCard(report, args, spec, localeOf(sender), command))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.failed("purge.failed", Component.text(unexpected(failure)), tr("purge.hint.unknown"))
            }
        }
    }

    private fun purge(sender: CommandSender, spec: PurgeSpec) {
        if (!claim(sender)) return
        sender.send("purge.start")
        services.scope.launch {
            try {
                services.flushCapture()
                val report = purgeSome(services.storage, spec)
                sender.say(report(report, localeOf(sender)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.failed("purge.failed", Component.text(unexpected(failure)), tr("purge.hint.unknown"))
            } finally {
                services.purging.set(false)
            }
        }
    }

    private fun everything(sender: CommandSender, args: PurgeArgs, command: String) {
        val locale = localeOf(sender)
        if (!args.confirmed) {
            if (args.continued) return sender.confirm()
            val size = services.storage.engine.stats().liveBytes / MIB
            sender.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("purge.preview.title"),
                    Component.empty(),
                    tr("purge.preview.all"),
                    tr("common.label.size", "size" to "%.1f".format(locale, size)),
                    Component.empty(),
                    buttons(command),
                ),
            )
            return
        }
        if (!claim(sender)) return

        services.scope.launch {
            try {
                val summary = purgeAll(services.storage)
                services.repo.forget()
                services.counters.forget()
                services.differ.forgetAll()
                sender.say(wiped(summary, locale))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.failed("purge.failed", Component.text(unexpected(failure)), tr("purge.hint.unknown"))
            } finally {
                services.purging.set(false)
            }
        }
    }

    private fun claim(sender: CommandSender): Boolean {
        if (services.composite.isRunning || !services.purging.compareAndSet(false, true)) {
            sender.send("common.busy")
            return false
        }
        return true
    }

    private fun previewCard(
        report: PurgeReport,
        args: PurgeArgs,
        spec: PurgeSpec,
        locale: Locale,
        command: String,
    ): Component {
        if (report.matched == 0L) return tr("purge.preview.nothing")

        val lines = mutableListOf(tr("purge.preview.title"), Component.empty())
        lines += tr("purge.preview.categories", "list" to spec.categories.map(::label).joined())
        args.olderMillis?.let { lines += tr("purge.preview.older", "span" to shortSpan(it)) }
        args.world?.let { lines += tr("purge.preview.world", "name" to it) }
        args.player?.let { lines += tr("purge.preview.player", "name" to it) }
        lines += Component.empty()

        for ((category, tally) in report.tallies) {
            lines += tr(
                "purge.preview.line",
                "category" to label(category),
                "matched" to "%,d".format(locale, tally.matched),
                "total" to "%,d".format(locale, tally.total),
            )
        }
        records("common.label.records", report.oldest, report.newest, locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, report.rows))
        lines += Component.empty()
        lines += buttons(command)
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun buttons(command: String): Component {
        val yes = tr("purge.button.continue").clickEvent(ClickEvent.runCommand("$command ${PurgeArgument.CONTINUE}"))
            .hoverEvent(HoverEvent.showText(tr("purge.button.continue_hover")))
        val backup = tr("purge.button.backup").clickEvent(ClickEvent.suggestCommand("/tracel export"))
            .hoverEvent(HoverEvent.showText(tr("purge.button.backup_hover")))
        return yes.append(Component.space()).append(backup)
    }

    private fun report(report: PurgeReport, locale: Locale): Component {
        val lines = mutableListOf(tr("purge.done"), Component.empty())
        for ((category, tally) in report.tallies) {
            lines += tr(
                "purge.report.line",
                "category" to label(category),
                "matched" to "%,d".format(locale, tally.matched),
            )
        }
        records("common.label.records", report.oldest, report.newest, locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, report.rows))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun wiped(summary: PurgeSummary, locale: Locale): Component {
        val lines = mutableListOf(
            tr("purge.done"),
            Component.empty(),
            tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        )
        records("common.label.records", summary.oldest, summary.newest, locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun label(category: PurgeCategory): Component = tr("purge.category.${category.name.lowercase()}")

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH
}
