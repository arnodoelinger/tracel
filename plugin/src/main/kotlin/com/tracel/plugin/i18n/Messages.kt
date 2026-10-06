package com.tracel.plugin.i18n

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.util.*
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.plugin.Plugin

/** `Tracel`'s texts. */
object Messages {
    const val PREFIX = "tracel."

    internal val LOCALES: List<Locale> = listOf(Locale.ENGLISH, Locale.of("ru"))

    private var store: MiniMessageTranslationStore? = null

    /** Loads or reloads every language. */
    fun load(plugin: Plugin) {
        install(LOCALES.associateWith { locale ->
            val name = "lang/${locale.language}.json"
            merged(plugin.getResource(name), plugin.dataFolder.resolve(name)) { plugin.logger.severe(it) }
        })
    }

    /** Replaces whatever was loaded with [tables]: prefixed keys to `MiniMessage`, by language. */
    internal fun install(tables: Map<Locale, Map<String, String>>) {
        val fresh = MiniMessageTranslationStore.create(Key.key("tracel", "messages"))
        fresh.defaultLocale(Locale.ENGLISH)
        tables.forEach(fresh::registerAll)
        unload()
        GlobalTranslator.translator().addSource(fresh)
        GlobalTranslator.translator().addSource(LowerCase)
        store = fresh
    }

    /** Unloads the language store. */
    fun unload() {
        store?.let(GlobalTranslator.translator()::removeSource)
        GlobalTranslator.translator().removeSource(LowerCase)
        store = null
    }

    /** Merges the bundled and edited language files. */
    internal fun merged(bundled: InputStream?, edited: File, complain: (String) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        bundled?.use { out += read(it) }
        if (edited.isFile) {
            runCatching { edited.inputStream().use(::read) }
                .onSuccess { out += it }
                .onFailure { complain("${edited.path} is broken, using the built-in texts: ${it.message}") }
        }
        return out.mapKeys { PREFIX + it.key }
    }

    /** Reads a language file. */
    internal fun read(input: InputStream): Map<String, String> =
        input.reader(Charsets.UTF_8).use { flatten(JsonParser.parseReader(it), "") }

    private fun flatten(json: JsonElement, path: String): Map<String, String> = when {
        json.isJsonObject -> json.asJsonObject.entrySet()
            .flatMap { (key, value) -> flatten(value, if (path.isEmpty()) key else "$path.$key").entries }
            .associate { it.key to it.value }

        json.isJsonPrimitive -> mapOf(path to json.asString)
        else -> error("$path: expected text or an object")
    }
}
