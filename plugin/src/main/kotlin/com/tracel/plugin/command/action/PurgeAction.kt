package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.command.action.ExportAction.Companion.records
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.unexpected
import com.tracel.storage.ports.ops.PurgeSummary
import com.tracel.storage.ports.ops.purgeAll
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/** Action responsible for safely purging the `Tracel` database. */
class PurgeAction(private val services: TracelServices) {
    /** Deletes all `Tracel`'s history. */
    fun execute(sender: CommandSender) {
        if (services.composite.isRunning) {
            sender.send("common.busy")
            return
        }

        // TODO: add more guards...

        services.scope.launch {
            runCatching {
                val summary = purgeAll(services.storage)
                services.repo.forget()
                services.counters.forget()
                services.differ.forgetAll()
                summary
            }.onSuccess { sender.say(report(it, (sender as? Player)?.locale() ?: Locale.ENGLISH)) }
                .onFailure { sender.failed("purge.failed", Component.text(unexpected(it)), tr("purge.hint.unknown")) }
        }
    }

    private fun report(summary: PurgeSummary, locale: Locale): Component {
        val lines = mutableListOf(
            tr("purge.done"),
            Component.empty(),
            tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        )
        records("common.label.records", summary.oldest, summary.newest, locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows))
        return Component.join(JoinConfiguration.newlines(), lines)
    }
}
