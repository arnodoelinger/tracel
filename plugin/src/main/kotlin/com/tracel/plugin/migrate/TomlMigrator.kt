package com.tracel.plugin.migrate

import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

private const val SECTION = "version"
private const val KEY = "version"

/** What a version of a TOML file changed in the one before it. */
internal sealed interface Change {
    /** Adds `key = literal` to [section], with [comment] above it, unless the section already has the key. */
    class AddKey(
        val section: String,
        val key: String,
        val literal: String,
        val comment: List<String> = emptyList(),
    ) : Change

    /** Renames a key where it stands, keeping its value, unless the new name is already taken. */
    class RenameKey(val section: String, val from: String, val to: String) : Change
}

/** The changes that take a file to version [to] from the one before. */
internal class FileStep(val to: Int, val changes: List<Change>)

/** [text] after migrating: [from] is [to] when nothing was done to it. */
internal class Migrated(val text: String, val from: Int, val to: Int) {
    val changed: Boolean get() = from != to
}

/**
 * Brings a TOML file a person edits up to date with the build that reads it, without touching what they wrote:
 * their values, comments, and order stay as they are, new options are added, renamed ones are renamed.
 *
 * The version lives in the file, in a `[version]` table kept at the very bottom. A file without one is older than
 * versions and counts as version 0.
 */
internal object TomlMigrator {
    /** The version [text] says it is, `0` if it says none, or `null` if it is not TOML that can be read. */
    fun versionOf(text: String): Int? {
        val parsed = Toml.parse(text)
        if (parsed.hasErrors()) return null
        return parsed.getLong("$SECTION.$KEY")?.toInt() ?: 0
    }

    /** [text] brought to [current] by the [steps] after the version it is at; unchanged if it is there or past it. */
    fun migrate(text: String, current: Int, steps: List<FileStep>): Migrated {
        val from = versionOf(text) ?: return Migrated(text, 0, 0)
        if (from >= current) return Migrated(text, from, from)

        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines().toMutableList()
        for (step in steps.filter { it.to > from && it.to <= current }.sortedBy { it.to }) {
            for (change in step.changes) when (change) {
                is Change.AddKey -> addKey(lines, change)
                is Change.RenameKey -> renameKey(lines, change)
            }
        }
        stamp(lines, current)
        return Migrated(lines.joinToString(newline), from, current)
    }

    /**
     * Migrates [file] in place and says so in one line. A file newer than [current] is left alone with a warning:
     * what it has that this build does not know is ignored.
     */
    fun migrateFile(file: Path, current: Int, steps: List<FileStep>, log: Logger, announce: Boolean = true) {
        if (!Files.isRegularFile(file)) return
        val name = file.fileName
        try {
            val migrated = migrate(Files.readString(file), current, steps)
            if (!migrated.changed) {
                if (migrated.from > current) {
                    log.warning("$name is version ${migrated.from}, newer than the $current this Tracel reads; what it does not know is ignored.")
                }
                return
            }
            val temp = file.resolveSibling("$name.tmp")
            Files.writeString(temp, migrated.text)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            if (announce) log.info("Updated $name to version ${migrated.to}.")
        } catch (failure: java.io.IOException) {
            log.warning("Could not update $name: ${failure.message}.")
        }
    }

    private fun addKey(lines: MutableList<String>, change: Change.AddKey) {
        val header = sectionAt(lines, change.section)
        if (header < 0) {
            val above = sectionAt(lines, SECTION).takeIf { it >= 0 } ?: run {
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeLast()
                lines.size
            }
            val block = listOf("[${change.section}]") + change.comment + "${change.key} = ${change.literal}"
            if (above == lines.size) {
                if (lines.isNotEmpty()) lines += ""
                lines += block
                lines += ""
            } else {
                lines.addAll(above, block + "")
            }
            return
        }
        val end = sectionEnd(lines, header)
        if ((header + 1 until end).any { keyOf(lines[it]) == change.key }) return
        var at = end
        while (at > header + 1 && lines[at - 1].isBlank()) at--
        lines.addAll(at, listOf("") + change.comment + "${change.key} = ${change.literal}")
    }

    private fun renameKey(lines: MutableList<String>, change: Change.RenameKey) {
        val header = sectionAt(lines, change.section)
        if (header < 0) return
        val range = header + 1 until sectionEnd(lines, header)
        if (range.any { keyOf(lines[it]) == change.to }) return
        val at = range.firstOrNull { keyOf(lines[it]) == change.from } ?: return
        val line = lines[at]
        val indent = line.length - line.trimStart().length
        lines[at] = line.substring(0, indent) + change.to + line.substring(line.indexOf(change.from, indent) + change.from.length)
    }

    private fun stamp(lines: MutableList<String>, current: Int) {
        val header = sectionAt(lines, SECTION)
        if (header < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeLast()
            if (lines.isNotEmpty()) lines += ""
            lines += "[$SECTION]"
            lines += "# Config version. Don't change this"
            lines += "$KEY = $current"
            lines += ""
            return
        }
        val range = header + 1 until sectionEnd(lines, header)
        val at = range.firstOrNull { keyOf(lines[it]) == KEY }
        if (at != null) lines[at] = "$KEY = $current" else lines.add(header + 1, "$KEY = $current")
    }

    private fun sectionAt(lines: List<String>, name: String): Int = lines.indexOfFirst { it.trim() == "[$name]" }

    private fun sectionEnd(lines: List<String>, header: Int): Int {
        for (i in header + 1 until lines.size) if (lines[i].trim().startsWith("[")) return i
        return lines.size
    }

    private fun keyOf(line: String): String? {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("[")) return null
        return text.substringBefore('=').trim().takeIf { '=' in text }
    }
}

/** What the config files changed from one version to the next. */
internal object FileVersions {
    val CONFIG_STEPS: List<FileStep> = emptyList()

    const val NOTE = "# Config version. Don't change this"
    const val PRESETS_NOTE = "# Presets version. Don't change this"
}
