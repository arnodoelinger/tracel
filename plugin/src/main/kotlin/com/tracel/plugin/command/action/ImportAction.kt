package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.i18n.*
import com.tracel.storage.ports.ops.ExportSummary
import com.tracel.storage.ports.ops.StoppedByRequest
import com.tracel.storage.ports.ops.importFrom
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.*
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
        val path = exportFile(fileName) ?: return badName(sender, fileName)
        val locale = localeOf(sender)
        sender.say(
            Component.join(
                JoinConfiguration.newlines(),
                tr("import.preview.title"),
                Component.empty(),
                tr("common.label.database", "file" to fileName),
                tr(
                    "common.label.size",
                    "size" to "%.1f".format(locale, runCatching { Files.size(path) }.getOrDefault(0L) / MIB)
                ),
                info(tr("import.preview.scope")),
                Component.empty(),
                confirmFooter("/tracel data import $fileName"),
            ),
        )
    }

    /** Replaces the database with the snapshot [fileName] and drops every cache that still remembers the old one. */
    fun execute(sender: CommandSender, fileName: String) {
        val path = exportFile(fileName)
        if (path == null) return badName(sender, fileName)

        if (!services.purging.compareAndSet(false, true)) {
            sender.send("common.busy")
            return
        }
        if (!services.composite.claimGate()) {
            services.purging.set(false)
            sender.send("common.busy")
            return
        }

        phase.set(CHECKING)
        sender.send("import.start", "file" to fileName)

        services.scope.launch {
            val done = try {
                runCatching {
                    importFrom(
                        services.storage,
                        path,
                        stopped = { phase.get() == STOPPING },
                        commit = { phase.compareAndSet(CHECKING, REPLACING) },
                    )
                }
            } finally {
                phase.set(IDLE)
                services.composite.releaseGate()
                services.purging.set(false)
            }
            done.onSuccess {
                services.repo.forget()
                services.counters.forget()
                services.differ.forgetAll()
                sender.say(report(it, localeOf(sender)))
            }.onFailure {
                if (it is StoppedByRequest) sender.send("import.stopped")
                else sender.fail(it)
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

    private fun exportFile(name: String): Path? {
        if (name.isBlank() || !name.endsWith(".tracel")) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val candidate = services.exportDirectory.resolve(name).normalize()
        if (candidate.parent != services.exportDirectory.normalize()) return null
        return candidate.takeIf { Files.isRegularFile(it) }
    }

    private fun report(summary: ExportSummary, locale: Locale): Component = Component.join(
        JoinConfiguration.newlines(),
        tr("import.done"),
        Component.empty(),
        tr("common.label.database", "file" to summary.file.fileName.toString()),
        tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows)),
    )

    private fun CommandSender.fail(reason: Throwable) {
        val (why, hint) = when (reason) {
            is AccessDeniedException, is ReadOnlyFileSystemException -> tr(
                "common.reason.denied",
                "folder" to services.exportDirectory.toString()
            ) to tr("common.hint.denied")

            is NoSuchFileException -> tr(
                "import.reason.missing",
                "file" to reason.file.substringAfterLast('/')
            ) to tr("import.hint.missing")

            is FileSystemException if reason.reason?.contains("No space left") == true ->
                tr("common.reason.no_space") to tr("common.hint.no_space")

            else -> Component.text(unexpected(reason)) to tr("common.hint.unknown")
        }
        failed("import.failed", why, hint)
    }

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH
}
