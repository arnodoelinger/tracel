package com.tracel.plugin.command.suggest

import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.nio.file.Files
import java.nio.file.Path

/** Suggestions for the file argument of `/tracel export import`. */
internal object ExportSuggest {
    private data class Snapshot(val name: String, val kib: Long?)

    /** Tab completion for snapshot files in [directory]. */
    fun suggesting(directory: Path): SuggestionProvider<CommandSourceStack> = SuggestionProvider { _, builder ->
        builder.reply(files(directory, builder.remaining))
    }

    /**
     * Retrieves a list of suggestions based on files in the specified directory
     * that match a given partial string.
     *
     * @return a list of suggestions where each suggestion represents a file whose name starts
     * with the partial string.
     */
    fun files(directory: Path, partial: String): List<Suggestion> {
        val needle = partial.lowercase()
        return listSnapshots(directory)
            .filter { it.name.lowercase().startsWith(needle) }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                val size = file.kib?.let { "$it KiB snapshot" } ?: "Snapshot file"
                Suggestion(file.name, size)
            }
    }

    private fun listSnapshots(directory: Path): List<Snapshot> = runCatching {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tracel") }
                .map { path ->
                    val kib = runCatching { Files.size(path) / 1024 }.getOrNull()
                    Snapshot(path.fileName.toString(), kib)
                }
                .toList()
        }
    }.getOrDefault(emptyList())
}
