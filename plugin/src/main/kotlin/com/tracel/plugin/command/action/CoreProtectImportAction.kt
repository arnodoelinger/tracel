package com.tracel.plugin.command.action

import com.tracel.engine.foreign.NoRoomForImport
import com.tracel.plugin.command.action.ExportAction.Companion.records
import com.tracel.plugin.i18n.*
import com.tracel.plugin.importer.coreprotect.*
import com.tracel.plugin.importer.coreprotect.source.CoreProtectDatabase
import com.tracel.plugin.importer.coreprotect.source.CoreProtectLocation
import com.tracel.plugin.importer.coreprotect.source.CoreProtectLocator
import com.tracel.plugin.importer.coreprotect.translate.ServerImportPlatform
import com.tracel.plugin.services.TracelServices
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/tracel data migrate coreprotect`: adds what `CoreProtect` recorded to the history, under what was recorded here.
 * Nothing is replaced, and running it twice takes nothing twice.
 */
class CoreProtectImportAction(private val services: TracelServices) {
    private val importer =
        CoreProtectImport(services.foreign, ServerImportPlatform) { work -> services.purgeGate.slice(slice = work) }

    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** Says what an import would take, without taking it. */
    fun preview(sender: CommandSender) {
        val location = locate(sender) ?: return
        services.scope.launch {
            try {
                val outlook =
                    withContext(Dispatchers.IO) { CoreProtectDatabase.open(location).use { importer.outlook(it) } }
                sender.say(card(outlook, location, localeOf(sender)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.fail(failure)
            }
        }
    }

    /** Imports what is left of the database. */
    fun execute(sender: CommandSender) {
        val location = locate(sender) ?: return
        if (!services.purging.compareAndSet(false, true)) return sender.send("common.busy")
        running.set(true)
        stopping.set(false)
        sender.send("import.coreprotect.start")
        services.plugin.logger.info("CoreProtect import from $location started by ${sender.name}.")

        services.scope.launch {
            try {
                services.purgeGate.awaitIdle { sender.send("purge.waiting") }
                val locale = localeOf(sender)
                var told = System.currentTimeMillis()
                val outcome = withContext(Dispatchers.IO) {
                    CoreProtectDatabase.open(location).use { database ->
                        importer.run(database, stopping::get) { done, of ->
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
                    }
                }
                sender.say(report(outcome, locale))
                services.plugin.logger.info(
                    "CoreProtect import ${if (outcome.stopped) "paused" else "finished"}: " +
                            "${outcome.tally.taken.values.sum()} record(s) from ${outcome.tally.rows} row(s), ${
                                shortSpan(
                                    outcome.tookMillis
                                )
                            }."
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.fail(failure)
            } finally {
                running.set(false)
                services.purging.set(false)
            }
        }
    }

    /** Stops the running import after the batch it is on. What it took stays, and the next run carries on. */
    fun stop(sender: CommandSender) {
        if (!running.get()) return sender.send("import.coreprotect.not_running")
        stopping.set(true)
        sender.send("import.coreprotect.stop")
    }

    private fun locate(sender: CommandSender): CoreProtectLocation? {
        val plugins = services.plugin.dataFolder.toPath().toAbsolutePath().parent
        val found = CoreProtectLocator.find(plugins)
        if (found == null) {
            sender.failed(
                "import.failed",
                tr("import.coreprotect.reason.not_found"),
                tr("import.coreprotect.hint.not_found")
            )
        }
        return found
    }

    private fun card(outlook: ImportOutlook, location: CoreProtectLocation, locale: Locale): Component {
        val left = outlook.left.sum()
        val lines = mutableListOf(
            tr("import.coreprotect.title"),
            Component.empty(),
            tr("common.label.database", "file" to location.toString()),
        )
        outlook.span?.let { (oldest, newest) ->
            records(
                "common.label.records",
                oldest * MILLIS,
                newest * MILLIS,
                locale
            )?.let { lines += it }
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

    private fun report(outcome: ImportOutcome, locale: Locale): Component {
        val tally = outcome.tally
        val lines =
            mutableListOf(tr(if (outcome.stopped) "import.coreprotect.paused" else "import.done"), Component.empty())
        lines += tr("import.coreprotect.taken", "count" to count(tally.taken.values.sum().toLong(), locale))
        if (tally.oldest <= tally.newest) records(
            "common.label.records",
            tally.oldest,
            tally.newest,
            locale
        )?.let { lines += it }
        val skipped = tally.skipped.values.sum().toLong()
        if (skipped > 0) lines += tr("import.coreprotect.skipped", "count" to count(skipped, locale))
        lines += tr("common.label.time", "time" to shortSpan(outcome.tookMillis))
        if (outcome.stopped) lines += tryHint(tr("import.coreprotect.hint.paused"))
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun CommandSender.fail(reason: Throwable) {
        if (reason is NoRoomForImport) {
            services.plugin.logger.warning("CoreProtect import refused: this database numbers its own history from the start.")
            return failed(
                "import.failed",
                tr("import.coreprotect.reason.no_room"),
                tr("import.coreprotect.hint.no_room")
            )
        }
        services.plugin.logger.log(Level.WARNING, "CoreProtect import failed", reason)
        failed(
            "import.failed",
            tr("import.coreprotect.reason.unreadable", "reason" to unexpected(reason)),
            tr("import.coreprotect.hint.unreadable"),
        )
    }

    private fun count(value: Long, locale: Locale): String = "%,d".format(locale, value)

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH

    private companion object {
        const val PROGRESS_EVERY_MILLIS = 5_000L
        const val PERCENT = 100
        const val MILLIS = 1_000L
    }
}
