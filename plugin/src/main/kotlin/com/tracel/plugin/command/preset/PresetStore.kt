package com.tracel.plugin.command.preset

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A saved set of flags. [owner] `null` is the server's own: everyone sees it, a personal one of the same name wins. */
internal data class Preset(val name: String, val owner: UUID?, val tokens: List<String>) {
    val text: String get() = tokens.joinToString(" ")
}

/**
 * Presets on disk, one per line: `owner<TAB>name<TAB>flags`, `*` for the server's own.
 *
 * A file that cannot be read is an empty store, not a failed start; a line that cannot be read is skipped.
 */
internal class PresetStore(private val file: Path) {
    private val presets = ConcurrentHashMap<String, Preset>()

    init {
        runCatching {
            if (Files.isRegularFile(file)) {
                for (line in Files.readAllLines(file)) parse(line)?.let { presets[key(it.owner, it.name)] = it }
            }
        }
    }

    /** The preset [name] as [owner] sees it: their own, else the server's. */
    fun find(name: String, owner: UUID?): Preset? =
        (owner?.let { presets[key(it, name.lowercase())] }) ?: presets[key(null, name.lowercase())]

    /** Everything [owner] can use, their own first, alphabetical; a personal preset hides the server's namesake. */
    fun visibleTo(owner: UUID?): List<Preset> {
        val mine = presets.values.filter { owner != null && it.owner == owner }
        val shared = presets.values.filter { it.owner == null && mine.none { own -> own.name == it.name } }
        return (mine.sortedBy { it.name } + shared.sortedBy { it.name })
    }

    /** Saves [preset], replacing one with the same name and owner. */
    @Synchronized
    fun save(preset: Preset) {
        presets[key(preset.owner, preset.name)] = preset
        persist()
    }

    /** @return whether there was one to delete. */
    @Synchronized
    fun delete(name: String, owner: UUID?): Boolean {
        val gone = presets.remove(key(owner, name.lowercase())) != null
        if (gone) persist()
        return gone
    }

    /**
     * Hands [name] from [from] to [to], the server being `null`.
     *
     * @return `false` when [from] has none, or [to] already has one of that name: nothing is overwritten.
     */
    @Synchronized
    fun move(name: String, from: UUID?, to: UUID?): Boolean {
        val lower = name.lowercase()
        val moving = presets[key(from, lower)] ?: return false
        if (presets.containsKey(key(to, lower))) return false
        presets.remove(key(from, lower))
        presets[key(to, lower)] = moving.copy(owner = to)
        persist()
        return true
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            val lines = presets.values
                .sortedWith(compareBy({ it.owner?.toString() ?: "" }, { it.name }))
                .map { "${it.owner ?: "*"}\t${it.name}\t${it.text}" }
            Files.write(temp, lines)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun key(owner: UUID?, name: String) = "${owner ?: "*"}/$name"

    private fun parse(line: String): Preset? {
        val parts = line.split('\t')
        if (parts.size != 3 || parts[1].isBlank()) return null
        val owner = if (parts[0] == "*") null else runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
        return Preset(parts[1], owner, parts[2].split(' ').filter { it.isNotBlank() })
    }

    companion object {
        private val NAME = Regex("""[a-z0-9_-]{1,24}""")

        /** Presets are named lowercase: `@grief`, not `@Grief`. */
        fun validName(name: String): Boolean = NAME.matches(name)
    }
}

/** The store the commands and their completions share; set when the commands are registered. */
internal object Presets {
    @Volatile
    var store: PresetStore? = null
}
