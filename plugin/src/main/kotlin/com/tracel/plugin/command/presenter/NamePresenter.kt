package com.tracel.plugin.command.presenter

import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.Registry

/**
 * A block, item, or entity as the player's own client names it: e.g. `minecraft:oak_leaves` becomes
 * "Oak Leaves", in their language.
 */
internal object NamePresenter {
    /** A kind of item. */
    fun of(id: String): Component = named(id, entity = false)

    /** A kind of entity. */
    fun entity(id: String): Component = named(id, entity = true)

    /** E.g. `minecraft:large_fern` -> `Large Fern`; what is left when the server cannot name it. */
    fun pretty(id: String): String =
        id.substringAfter(':').lowercase().split('_').filter(String::isNotEmpty)
            .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

    private fun named(id: String, entity: Boolean): Component {
        val bare = id.substringBefore('[')
        val key = NamespacedKey.fromString(bare.lowercase())
        val translation = key?.let { runCatching { translationKey(it, entity) }.getOrNull() }
        return if (translation != null) Component.translatable(translation) else Component.text(pretty(bare))
    }

    private fun translationKey(key: NamespacedKey, entity: Boolean): String? {
        val material = { Registry.MATERIAL.get(key)?.translationKey() }
        val type = { Registry.ENTITY_TYPE.get(key)?.translationKey() }
        return if (entity) type() ?: material() else material() ?: type()
    }
}
