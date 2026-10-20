package com.tracel.plugin.command.action

import com.tracel.api.admin.AdminError
import com.tracel.api.admin.MigrationOutcome
import com.tracel.api.admin.MigrationOutlook
import com.tracel.api.admin.MigrationSource
import com.tracel.api.result.Res
import com.tracel.plugin.command.action.ExportAction.Companion.records
import com.tracel.plugin.command.action.support.describe
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/tracel data migrate coreprotect`: adds what `CoreProtect` recorded to the history, under what was recorded here.
 * Nothing is replaced, and running it twice takes nothing twice.
 */
class CoreProtectImportAction(private val services: TracelServices) {
    private val migration get() = services.commandApi.admin.migration

    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** Says what an import would take, without taking it. */
    fun preview(sender: CommandSender) {
        val source = locate(sender) ?: return
        services.scope.launch {
            when (val got = migration.outlook(source)) {
                is Res.Ok -> sender.say(card(got.value, source, localeOf(sender)))
                is Res.Err -> sender.fail(got.error)
            }
        }
    }

    /** Imports what is left of the database. */
    fun execute(sender: CommandSender) {
        val source = locate(sender) ?: return
        services.scope.launch {
            val locale = localeOf(sender)
            var told = System.currentTimeMillis()
            val done = try {
                migration.run(
                    source,
                    stop = stopping::get,
                    onStart = {
                        running.set(true)
                        stopping.set(false)
                        sender.send("import.coreprotect.start")
                    },
                    onWait = { sender.send("purge.waiting") },
                ) { done, of ->
                    val now = System.currentTimeMillis()
                    if (now - told < PROGRESS_EVERY_MILLIS || done >= of) return@run
                    told = now
                    sender.send(
                        "import.coreprotect.progress",
                        "percent" to (done * PERCENT / of).toString(),
                        "done" to count(done, locale),
                        "rows" to count(of, locale),
                    )
                }
            } finally {
                running.set(false)
            }
            when (done) {
                is Res.Ok -> sender.say(report(done.value, locale))
                is Res.Err -> if (done.error == AdminError.Busy) sender.send("common.busy") else sender.fail(done.error)
            }
        }
    }

    /** Stops the running import after the batch it is on. What it took stays, and the next run carries on. */
    fun stop(sender: CommandSender) {
        if (!running.get()) return sender.send("import.coreprotect.not_running")
        stopping.set(true)
        sender.send("import.coreprotect.stop")
    }

    private fun locate(sender: CommandSender): MigrationSource? =
        when (val found = migration.locate()) {
            is Res.Ok -> found.value
            is Res.Err -> {
                sender.failed(
                    "import.failed",
                    tr("import.coreprotect.reason.not_found"),
                    tr("import.coreprotect.hint.not_found")
                )
                null
            }
        }

    private fun card(outlook: MigrationOutlook, location: MigrationSource, locale: Locale): Component {
        val left = outlook.rowsLeft
        val lines = mutableListOf(
            tr("import.coreprotect.title"),
            Component.empty(),
            tr("common.label.database", "file" to location.toString()),
        )
        outlook.span?.let { (oldest, newest) ->
            records("common.label.records", oldest.toEpochMilli(), newest.toEpochMilli(), locale)?.let { lines += it }
        }
        lines += tr("common.label.rows", "rows" to count(left, locale))
        lines += Component.empty()
        if (left <= 0) {
            lines += tr("import.coreprotect.nothing_left")
        } else {
            lines += info(tr("import.coreprotect.scope"))
            lines += Component.empty()
            lines += confirmFooter("/tracel data migrate coreprotect")
        }
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun report(outcome: MigrationOutcome, locale: Locale): Component {
        val lines =
            mutableListOf(tr(if (outcome.stopped) "import.coreprotect.paused" else "import.done"), Component.empty())
        lines += tr("import.coreprotect.taken", "count" to count(outcome.taken, locale))
        records("common.label.records", outcome.oldest?.toEpochMilli(), outcome.newest?.toEpochMilli(), locale)
            ?.let { lines += it }
        if (outcome.skipped > 0) lines += tr("import.coreprotect.skipped", "count" to count(outcome.skipped, locale))
        lines += tr("common.label.time", "time" to shortSpan(outcome.tookMillis))
        if (outcome.stopped) lines += tryHint(tr("import.coreprotect.hint.paused"))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun CommandSender.fail(error: AdminError) {
        if (error == AdminError.NoRoom) {
            return failed(
                "import.failed",
                tr("import.coreprotect.reason.no_room"),
                tr("import.coreprotect.hint.no_room")
            )
        }
        failed(
            "import.failed",
            tr("import.coreprotect.reason.unreadable", "reason" to error.describe()),
            tr("import.coreprotect.hint.unreadable"),
        )
    }

    private fun count(value: Long, locale: Locale): String = "%,d".format(locale, value)

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH

    private companion object {
        const val PROGRESS_EVERY_MILLIS = 5_000L
        const val PERCENT = 100
    }
}
