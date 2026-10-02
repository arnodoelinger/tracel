package com.tracel.plugin.i18n

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.Message
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.translation.Argument
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import java.util.Locale
import kotlin.math.abs

private val MESSAGES: MessageComponentSerializer? = runCatching { MessageComponentSerializer.message() }.getOrNull()

/** A message by key, translated when it reaches a player. */
fun tr(key: String, vararg args: Pair<String, Any?>): Component =
    Component.translatable(Messages.PREFIX + key, args.map { (name, value) -> argument(name, value) })

/**
 * The text under [key] with its first letter in lower case, for a word the language file keeps capitalized:
 * "Placed" in the file, "placed" in the middle of a row. The language does the changing, see [LowerCase].
 */
fun lower(key: String): Component = Component.translatable(LowerCase.PREFIX + key)

/** Sends [tr] of [key] as one chat line. */
fun Audience.send(key: String, vararg args: Pair<String, Any?>) = say(tr(key, *args))

/** Every chat line Tracel writes: the `prefix` mark, then the line in white. */
fun Audience.say(line: ComponentLike) =
    sendMessage(Component.text().append(tr("prefix")).append(Component.text().color(NamedTextColor.WHITE).append(line)))

/**
 * A command that needs one more word from the player before it runs: the orange headline, what [info] says
 * about why it stops, and what to try.
 */
fun Audience.needed(info: Component, hint: Component) = say(
    Component.join(
        JoinConfiguration.newlines(),
        tr("common.needed.title"),
        Component.empty(),
        tr("common.label.info", "info" to info),
        tr("common.label.hint", "hint" to hint),
    ),
)

/** `running it again with #confirm`: typed by hand, never a click away. */
fun confirmHint(): Component = tr("common.needed.run", "command" to "#confirm")

/** The command is destructive and was run without `#confirm`: says so, and how to run it for real. */
fun Audience.confirm() = needed(tr("common.needed.destructive"), confirmHint())

/** The one line a [command] says when it is run without what it needs: how to run it. */
fun Audience.usage(command: String) = send("common.usage", "command" to command)

/** Joins with [separator], plain text between the parts. */
fun List<ComponentLike>.joined(separator: String = ", "): Component =
    Component.join(JoinConfiguration.separator(Component.text(separator)), this)

/**
 * A tab tooltip. `Paper` translates it per player on the way out; without a server (tests) it is
 * flattened to English plain text.
 */
fun Component.asMessage(): Message = MESSAGES?.serialize(this)
    ?: LiteralMessage(PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(this, Locale.ENGLISH)))

/** A failure told the same way everywhere: the red headline under [title], then why and what to try. */
fun Audience.failed(title: String, reason: Component, hint: Component) = say(
    Component.join(
        JoinConfiguration.newlines(),
        tr(title),
        Component.empty(),
        tr("common.label.reason", "reason" to reason),
        tr("common.label.hint", "hint" to hint),
    )
)

/** A line that starts with the gray "Info:" label. */
fun info(text: Component): Component = tr("common.label.info", "info" to text)

/** A line that starts with the gray "Try:" label. */
fun tryHint(text: Component): Component = tr("common.label.hint", "hint" to text)

/** Several reasons for one failure, on the one `Reason:` line: "no time, no scope". */
fun List<Component>.asReason(): Component = commas()

/** [this] on one line, "a, b, c". */
fun List<Component>.commas(): Component = Component.join(JoinConfiguration.separator(Component.text(", ")), this)

/** What an exception said when nothing better is known: no trailing period, and no capital unless it is a name (`IOException`). */
fun unexpected(failure: Throwable): String {
    val said = (failure.message ?: failure::class.java.simpleName).trimEnd('.', ' ')
    return if (said.length > 1 && said[1].isUpperCase()) said else said.replaceFirstChar(Char::lowercase)
}

/** The plural form of [n] in a language with [forms] forms. The first form is for 1, the second for 2, the third for 5, */
internal fun pluralForm(n: Long, forms: Int): Int {
    val abs = abs(n)
    return when (forms) {
        3 -> when {
            abs % 10 == 1L && abs % 100 != 11L -> 0
            abs % 10 in 2..4 && abs % 100 !in 12..14 -> 1
            else -> 2
        }

        else -> if (abs == 1L) 0 else minOf(1, forms - 1)
    }
}

private fun argument(name: String, value: Any?): ComponentLike = when (value) {
    is ComponentLike -> Argument.component(name, value)
    is Number -> Argument.tagResolver(plural(name, value))
    is Boolean -> Argument.bool(name, value)
    else -> Argument.string(name, if (name == "reason") value.toString().trimEnd('.', ' ') else value.toString())
}

private fun plural(name: String, value: Number): TagResolver = TagResolver.resolver(name) { args, _ ->
    val forms = generateSequence { if (args.hasNext()) args.pop().value() else null }.toList()
    Tag.inserting(Component.text(if (forms.isEmpty()) value.toString() else forms[pluralForm(value.toLong(), forms.size)]))
}
