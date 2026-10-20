package com.tracel.plugin.command.action

import com.tracel.plugin.adapter.command.resolvePlayerUuid
import com.tracel.api.admin.AdminError
import com.tracel.api.admin.PurgeCategory
import com.tracel.api.admin.PurgeReport
import com.tracel.api.admin.PurgeRequest
import com.tracel.api.admin.PurgeSummary
import com.tracel.api.result.Res
import com.tracel.plugin.command.action.support.describe
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.command.action.ExportAction.Companion.records
import com.tracel.plugin.command.args.purge.PurgeArgs
import com.tracel.plugin.command.args.purge.PurgeArgument
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.util.*

/**
 * `/tracel data purge`: takes history out of the database, all of it or only what the filters name.
 * Without `#confirm` it takes nothing: it previews, and `#confirm` is typed by hand.
 */
class PurgeAction(private val services: TracelServices) {
    /** Runs `/tracel data purge` with the words after it. */
    fun execute(sender: CommandSender, tokens: List<String>) {
        val args = PurgeArgument.parse(tokens)
        if (args.errors.isNotEmpty()) return refuse(sender, args.errors.asReason())
        if (args.isEmpty) return sender.usage("data purge")
        val command = "/tracel data purge " + tokens.filterNot { it.isStep() }.joinToString(" ")
        if (args.everything) return everything(sender, args, command)

        val request = requestOf(sender, args) ?: return
        when {
            args.confirmed -> purge(sender, request)
            else -> preview(sender, args, request, command)
        }
    }

    private fun refuse(sender: CommandSender, reason: Component) =
        sender.failed("purge.failed", reason, tr("common.hint.fix_flags", "command" to "data purge"))

    private fun requestOf(sender: CommandSender, args: PurgeArgs): PurgeRequest? {
        val problems = ArrayList<Component>()
        val world = args.world?.let { name ->
            val found = Bukkit.getWorlds().firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (found == null) problems += tr("common.reason.unknown_world", "name" to name)
            found?.uid
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
        val before = args.olderMillis?.let { Instant.ofEpochMilli(System.currentTimeMillis() - it) }
        return PurgeRequest(categories, before, world, player)
    }

    private fun String.isStep() = equals(PurgeArgument.CONFIRM, true)

    private fun preview(sender: CommandSender, args: PurgeArgs, request: PurgeRequest, command: String) {
        sender.send("purge.counting")
        services.scope.launch {
            when (val got = services.commandApi.admin.previewPurge(request)) {
                is Res.Ok -> sender.say(previewCard(got.value, args, request, localeOf(sender), command))
                is Res.Err -> failed(sender, got.error)
            }
        }
    }

    private fun purge(sender: CommandSender, request: PurgeRequest) {
        services.scope.launch {
            val got = services.commandApi.admin.purge(
                request,
                onStart = { sender.send("purge.start") },
                onWait = { sender.send("purge.waiting") },
            )
            when (got) {
                is Res.Ok -> sender.say(report(got.value, localeOf(sender)))
                is Res.Err -> failed(sender, got.error)
            }
        }
    }

    private fun everything(sender: CommandSender, args: PurgeArgs, command: String) {
        val locale = localeOf(sender)
        if (!args.confirmed) {
            val size = services.commandApi.admin.storeBytes / MIB
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

        services.scope.launch {
            when (val got = services.commandApi.admin.purgeEverything { sender.send("purge.waiting") }) {
                is Res.Ok -> sender.say(wiped(got.value, locale))
                is Res.Err -> failed(sender, got.error)
            }
        }
    }

    private fun failed(sender: CommandSender, error: AdminError) {
        if (error == AdminError.Busy) return sender.send("common.busy")
        sender.failed("purge.failed", Component.text(error.describe()), tr("purge.hint.unknown"))
    }

    private fun previewCard(
        report: PurgeReport,
        args: PurgeArgs,
        request: PurgeRequest,
        locale: Locale,
        command: String,
    ): Component {
        if (report.matched == 0L) return tr("purge.preview.nothing")

        val lines = mutableListOf(tr("purge.preview.title"), Component.empty())
        lines += tr("purge.preview.categories", "list" to request.categories.map(::label).joined())
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
        records("common.label.records", report.oldest?.toEpochMilli(), report.newest?.toEpochMilli(), locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, report.rows))
        lines += Component.empty()
        lines += buttons(command)
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun buttons(command: String): Component = confirmFooter(command)

    private fun report(report: PurgeReport, locale: Locale): Component {
        val lines = mutableListOf(tr("purge.done"), Component.empty())
        for ((category, tally) in report.tallies) {
            lines += tr(
                "purge.report.line",
                "category" to label(category),
                "matched" to "%,d".format(locale, tally.matched),
            )
        }
        records("common.label.records", report.oldest?.toEpochMilli(), report.newest?.toEpochMilli(), locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, report.rows))
        if (report.bytes > 0) lines += tr("purge.report.freed", "size" to "%.1f".format(locale, report.bytes / MIB))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun wiped(summary: PurgeSummary, locale: Locale): Component {
        val lines = mutableListOf(
            tr("purge.done"),
            Component.empty(),
            tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        )
        records("common.label.records", summary.oldest?.toEpochMilli(), summary.newest?.toEpochMilli(), locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun label(category: PurgeCategory): Component = tr("purge.category.${category.name.lowercase()}")

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH

}
