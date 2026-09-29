package com.tracel.plugin.i18n

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStream
import java.util.Locale

/** `Tracel`'s texts. */
object Messages {
    const val PREFIX = "tracel."

    internal val LOCALES: List<Locale> = listOf(Locale.ENGLISH, Locale.of("ru"))

    private var store: MiniMessageTranslationStore? = null

    /** Loads or reloads every language. */
    fun load(plugin: Plugin) {
        val fresh = MiniMessageTranslationStore.create(Key.key("tracel", "messages"))
        fresh.defaultLocale(Locale.ENGLISH)
        for (locale in LOCALES) {
            val name = "lang/${locale.language}.json"
            val edited = plugin.dataFolder.resolve(name)
            if (!edited.exists()) plugin.saveResource(name, false)
            fresh.registerAll(locale, merged(plugin.getResource(name), edited) { plugin.logger.severe(it) })
        }
        unload()
        GlobalTranslator.translator().addSource(fresh)
        store = fresh
    }

    /** Unloads the language store. */
    fun unload() {
        store?.let(GlobalTranslator.translator()::removeSource)
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
