package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.ExportAction.Companion.MIB
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.unexpected
import com.tracel.storage.ports.ops.ExportSummary
import com.tracel.storage.ports.ops.importFrom
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.ReadOnlyFileSystemException
import java.util.Locale

/** Loads a `Tracel` database snapshot back from the export directory. */
class ImportAction(private val services: TracelServices) {
    /** Replaces the database with the snapshot [fileName] and drops every cache that still remembers the old one. */
    fun execute(sender: CommandSender, fileName: String) {
        val path = exportFile(fileName)
        if (path == null) {
            sender.failed(
                "import.failed",
                tr("import.reason.bad_name", "file" to fileName),
                tr("import.hint.bad_name"),
            )
            return
        }

        if (!services.purging.compareAndSet(false, true)) {
            sender.send("common.busy")
            return
        }
        if (!services.composite.claimGate()) {
            services.purging.set(false)
            sender.send("common.busy")
            return
        }

        sender.send("import.start", "file" to fileName)

        services.scope.launch {
            val done = try {
                runCatching { importFrom(services.storage, path) }
            } finally {
                services.composite.releaseGate()
                services.purging.set(false)
            }
            done.onSuccess {
                services.repo.forget()
                services.counters.forget()
                services.differ.forgetAll()
                sender.say(report(it, localeOf(sender)))
            }.onFailure { sender.fail(it) }
        }
    }

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
            is AccessDeniedException, is ReadOnlyFileSystemException -> tr("common.reason.denied", "folder" to services.exportDirectory.toString()) to tr("common.hint.denied")
            is NoSuchFileException -> tr("import.reason.missing", "file" to reason.file.substringAfterLast('/')) to tr("import.hint.missing")
            is FileSystemException if reason.reason?.contains("No space left") == true ->
                tr("common.reason.no_space") to tr("common.hint.no_space")

            else -> Component.text(unexpected(reason)) to tr("common.hint.unknown")
        }
        failed("import.failed", why, hint)
    }

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH
}
