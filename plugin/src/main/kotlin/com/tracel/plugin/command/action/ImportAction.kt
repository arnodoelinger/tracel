package com.tracel.plugin.command.action

import com.tracel.api.admin.AdminError
import com.tracel.api.admin.ImportControl
import com.tracel.api.admin.TransferSummary
import com.tracel.api.result.Res
import com.tracel.plugin.command.action.support.describe
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/** Loads a `Tracel` database snapshot back from the export directory. */
class ImportAction(private val services: TracelServices) {
    private val phase = AtomicInteger(IDLE)

    private companion object {
        const val IDLE = 0
        const val CHECKING = 1
        const val STOPPING = 2
        const val REPLACING = 3
    }

    /** Shows what would be replaced, with the buttons that go on or take a backup first. */
    fun preview(sender: CommandSender, fileName: String) {
        services.scope.launch {
            val snapshot = when (val got = services.commandApi.admin.snapshot(fileName)) {
                is Res.Ok -> got.value
                is Res.Err -> return@launch badName(sender, fileName)
            }
            val locale = localeOf(sender)
            sender.say(
                Component.join(
                    JoinConfiguration.newlines(),
                    tr("import.preview.title"),
                    Component.empty(),
                    tr("common.label.database", "file" to fileName),
                    tr("common.label.size", "size" to "%.1f".format(locale, snapshot.bytes / MIB)),
                    info(tr("import.preview.scope")),
                    Component.empty(),
                    confirmFooter("/tracel data import $fileName"),
                ),
            )
        }
    }

    /** Replaces the database with the snapshot [fileName] and drops every cache that still remembers the old one. */
    fun execute(sender: CommandSender, fileName: String) {
        services.scope.launch {
            val control = object : ImportControl {
                override fun stopRequested(): Boolean = phase.get() == STOPPING

                override fun commit(): Boolean = phase.compareAndSet(CHECKING, REPLACING)
            }
            val done = try {
                services.commandApi.admin.importSnapshot(fileName, control) {
                    phase.set(CHECKING)
                    sender.send("import.start", "file" to fileName)
                }
            } finally {
                phase.set(IDLE)
            }
            when (done) {
                is Res.Ok -> sender.say(report(done.value, localeOf(sender)))
                is Res.Err -> when (val error = done.error) {
                    AdminError.Busy -> sender.send("common.busy")
                    AdminError.Stopped -> sender.send("import.stopped")
                    is AdminError.BadName -> badName(sender, fileName)
                    else -> sender.fail(error)
                }
            }
        }
    }

    /**
     * Stops the import while the file is still being checked;
     * once the old history is being replaced it is too late.
     */
    fun stop(sender: CommandSender) {
        when {
            phase.compareAndSet(CHECKING, STOPPING) -> sender.send("import.stop")
            phase.get() == REPLACING -> sender.send("import.too_late")
            phase.get() == STOPPING -> sender.send("import.stop")
            else -> sender.send("import.not_running")
        }
    }

    private fun badName(sender: CommandSender, fileName: String) = sender.failed(
        "import.failed",
        tr("import.reason.bad_name", "file" to fileName),
        tr("import.hint.bad_name"),
    )

    private fun report(summary: TransferSummary, locale: Locale): Component = Component.join(
        JoinConfiguration.newlines(),
        tr("import.done"),
        Component.empty(),
        tr("common.label.database", "file" to summary.file.fileName.toString()),
        tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows)),
    )

    private fun CommandSender.fail(error: AdminError) {
        val (why, hint) = when (error) {
            is AdminError.Denied -> tr(
                "common.reason.denied",
                "folder" to error.folder.toString()
            ) to tr("common.hint.denied")

            is AdminError.NoSuchFile -> tr("import.reason.missing", "file" to error.name) to tr("import.hint.missing")

            AdminError.NoSpace -> tr("common.reason.no_space") to tr("common.hint.no_space")

            else -> Component.text(error.describe()) to tr("common.hint.unknown")
        }
        failed("import.failed", why, hint)
    }

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH
}
