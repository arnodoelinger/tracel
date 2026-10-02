package com.tracel.plugin.command.preset

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.tomlj.Toml
import org.tomlj.TomlTable

/** A saved set of flags. [owner] `null` is the server's own: everyone sees it, a personal one of the same name wins. */
internal data class Preset(val name: String, val owner: UUID?, val tokens: List<String>) {
    val text: String get() = tokens.joinToString(" ")
}

/** Presets on disk as TOML, like the config. */
internal class PresetStore(private val file: Path) {
    private val presets = ConcurrentHashMap<String, Preset>()

    init {
        runCatching {
            if (Files.isRegularFile(file)) load() else persist()
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

    private fun load() {
        val parsed = Toml.parse(file)
        if (parsed.hasErrors()) {
            Files.copy(file, file.resolveSibling(file.fileName.toString() + ".broken"), StandardCopyOption.REPLACE_EXISTING)
            return
        }
        parsed.getTable("server")?.let { read(null, it) }
        parsed.getTable("players")?.let { players ->
            for (id in players.keySet()) {
                val owner = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                players.getTable(listOf(id))?.let { read(owner, it) }
            }
        }
    }

    private fun read(owner: UUID?, table: TomlTable) {
        for (name in table.keySet()) {
            val flags = table.getTable(listOf(name))?.getString("flags") ?: continue
            if (!validName(name)) continue
            presets[key(owner, name)] = Preset(name, owner, flags.split(' ').filter { it.isNotBlank() })
        }
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, render())
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun render(): String {
        val shared = presets.values.filter { it.owner == null }.sortedBy { it.name }
            .map { entry("server", it) }
        val personal = presets.values.filter { it.owner != null }.groupBy { it.owner!! }
            .toSortedMap(compareBy { it.toString() })
            .flatMap { (owner, own) -> own.sortedBy { it.name }.map { entry("players.$owner", it) } }
        return (listOf(HEADER.trimEnd()) + shared + personal).joinToString("\n\n", postfix = "\n")
    }

    private fun entry(path: String, preset: Preset): String {
        val flags = preset.text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "[$path.${preset.name}]\nflags = \"$flags\""
    }

    private fun key(owner: UUID?, name: String) = "${owner ?: "*"}/$name"

    companion object {
        private val HEADER: String =
            PresetStore::class.java.getResource("/presets.toml")?.readText().orEmpty()

        private val NAME = Regex("""[a-z0-9_-]{1,24}""")

        fun validName(name: String): Boolean = NAME.matches(name)
    }
}

/** The store the commands and their completions share; set when the commands are registered. */
internal object Presets {
    @Volatile
    var store: PresetStore? = null
}
