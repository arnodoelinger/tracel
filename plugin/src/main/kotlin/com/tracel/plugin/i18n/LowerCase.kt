package com.tracel.plugin.i18n

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import net.kyori.adventure.translation.Translator
import net.kyori.adventure.util.TriState
import java.text.MessageFormat
import java.util.Locale

/**
 * Answers the keys that [lower] builds: the text of the key behind them, in the reader's language, with the
 * first letter small.
 */
internal object LowerCase : Translator {
    const val PREFIX = Messages.PREFIX + "lower."

    override fun name(): Key = Key.key("tracel", "lowercase")

    override fun hasAnyTranslations(): TriState = TriState.TRUE

    override fun translate(key: String, locale: Locale): MessageFormat? = null

    override fun translate(component: TranslatableComponent, locale: Locale): Component? {
        val key = component.key()
        if (!key.startsWith(PREFIX)) return null
        val real = Component.translatable(Messages.PREFIX + key.removePrefix(PREFIX), component.arguments())
        val text = PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(real, locale))
        return Component.text(text.replaceFirstChar { it.lowercase(locale) })
    }
}
