package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.storage.ports.ops.exportTo
import com.tracel.storage.ports.ops.importFrom
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Action responsible for exporting and importing `Tracel` database snapshots. */
class ExportAction(private val services: TracelServices) {
    /** Exports the current `Tracel` database snapshot to a file in the export directory. */
    fun executeExport(sender: CommandSender) {
        val name = "database-%s.tracel".format(LocalDateTime.now().format(STAMP))
        val to = services.exportDirectory.resolve(name)

        sender.sendMessage("Exporting to $name — the server keeps running.")

        services.scope.launch {
            val done = runCatching { exportTo(services.storage, to) }
            done.onSuccess {
                sender.sendMessage("Exported ${it.rows} rows, ${it.bytes / 1024} KiB, to ${it.file.fileName}.")
            }.onFailure { sender.sendMessage("Export failed: ${it.message}") }
        }
    }

    /** Imports `Tracel` database snapshot from a file in the export directory. */
    fun executeImport(sender: CommandSender, fileName: String) {
        val path = exportFile(fileName)
        if (path == null) {
            sender.sendMessage(
                "Import: $fileName is not a file in the export folder — give the name of one, " +
                        "not a path (no slashes, no ..)."
            )
            return
        }

        sender.sendMessage("Importing $fileName — everything currently recorded is going away.")

        services.scope.launch {
            val done = runCatching { importFrom(services.storage, path) }
            done.onSuccess {
                services.repo.forget()
                services.differ.forgetAll()
                sender.sendMessage("Imported ${it.rows} rows from ${it.file.fileName}.")
            }.onFailure { sender.sendMessage("Import failed: ${it.message}") }
        }
    }

    /** @return the path to the exported file. */
    fun exportFile(name: String): Path? {
        if (name.isBlank() || !name.endsWith(".tracel")) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val candidate = services.exportDirectory.resolve(name).normalize()
        if (candidate.parent != services.exportDirectory.normalize()) return null
        return candidate.takeIf { Files.isRegularFile(it) }
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}
