package com.tracel.plugin.setup

import java.nio.file.Files
import java.nio.file.Path

/** Sets values in `config.toml` in place, keeping its comments and everything else as it is. */
internal object ConfigEdit {
    /** Sets `key` to the TOML [literal] under `[section]`, adding the key or the section when it is missing. */
    fun set(file: Path, section: String, key: String, literal: String) {
        val text = if (Files.exists(file)) Files.readString(file) else ""
        Files.writeString(file, with(text, section, key, literal))
    }

    /** [text] with `key` set to [literal] under `[section]`. */
    fun with(text: String, section: String, key: String, literal: String): String {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines().toMutableList()
        val header = lines.indexOfFirst { it.trim() == "[$section]" }
        if (header < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeLast()
            if (lines.isNotEmpty()) lines += ""
            lines += "[$section]"
            lines += "$key = $literal"
            return lines.joinToString(newline) + newline
        }
        var end = lines.size
        for (i in header + 1 until lines.size) {
            if (lines[i].trim().startsWith("[")) {
                end = i
                break
            }
        }
        val at = (header + 1 until end).firstOrNull { lines[it].trim().substringBefore('=').trim() == key }
        if (at != null) lines[at] = "$key = $literal" else lines.add(header + 1, "$key = $literal")
        return lines.joinToString(newline)
    }
}
