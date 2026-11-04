package com.tracel.plugin.util.text

import com.tracel.plugin.util.runtime.messageOrName
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.Message
import com.tracel.plugin.services.monitoring.Telemetry
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.translation.Argument
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import java.util.*
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
fun Audience.needed(info: Component, hint: Component, buttons: Component? = null) = say(
    newlines(
        listOfNotNull(
            tr("common.needed.title"),
            Component.empty(),
            tr("common.label.info", "info" to info),
            tr("common.label.hint", "hint" to hint),
            buttons?.let { Component.empty() },
            buttons,
        ),
    ),
)

/** `running it again with #confirm`: typed by hand, never a click away. */
fun confirmHint(): Component = tr("common.needed.run", "command" to "#confirm")

/** What closes a preview: the next step, "#confirm" (put in the chat box, never run by a click), and a backup to take first. */
fun confirmFooter(command: String, backup: Boolean = true): Component {
    val go = tr("common.button.continue").clickEvent(ClickEvent.suggestCommand("$command #confirm"))
        .hoverEvent(HoverEvent.showText(tr("common.button.continue_hover")))
    val buttons = if (!backup) go else {
        val first = tr("common.button.backup").clickEvent(ClickEvent.suggestCommand("/tracel data export"))
            .hoverEvent(HoverEvent.showText(tr("common.button.backup_hover")))
        go.append(Component.space()).append(first)
    }
    return newlines(tr("common.next.confirm"), Component.empty(), buttons)
}

/** The one line a [command] says when it is run without what it needs: how to run it. */
fun Audience.usage(command: String) = send("common.usage", "command" to command)

/** Joins with [separator], plain text between the parts. */
fun List<ComponentLike>.joined(separator: String = ", "): Component =
    Component.join(JoinConfiguration.separator(Component.text(separator)), this)

/** [parts], one per chat line. */
fun newlines(parts: Iterable<ComponentLike>): Component = Component.join(JoinConfiguration.newlines(), parts)

/** [parts], one per chat line. */
fun newlines(vararg parts: ComponentLike): Component = Component.join(JoinConfiguration.newlines(), *parts)

/** [this] as English plain text, for what must not depend on the reader's language. */
fun Component.plainEnglish(): String =
    PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(this, Locale.ENGLISH))

/** `large_fern` becomes `Large Fern`: [this] cut at [separators], the empty words dropped, each one capitalized. */
fun String.titleWords(vararg separators: Char): String =
    split(*separators).filter(String::isNotEmpty).joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

/** How long ago [millis] was, in the biggest unit that fits: `05s`, `12m`, `03h`, `42d`, `01y`. */
fun agoText(millis: Long): Component {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val (unit, n) = when {
        seconds < 60 -> "s" to seconds
        seconds < 3_600 -> "m" to seconds / 60
        seconds < 86_400 -> "h" to seconds / 3_600
        seconds < 31_536_000 -> "d" to seconds / 86_400
        else -> "y" to seconds / 31_536_000
    }
    return tr("lookup.ago.$unit", "n" to "%02d".format(n))
}

/**
 * A tab tooltip. `Paper` translates it per player on the way out; without a server (tests) it is
 * flattened to English plain text.
 */
fun Component.asMessage(): Message = MESSAGES?.serialize(this)
    ?: LiteralMessage(plainEnglish())

/** A failure told the same way everywhere: the red headline under [title], then why and what to try. */
fun Audience.failed(title: String, reason: Component, hint: Component) = say(
    newlines(
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
fun List<Component>.asReason(): Component = joined()

/** What an exception said when nothing better is known: no trailing period, and no capital unless it is a name (`IOException`). */
fun unexpected(failure: Throwable): String {
    Telemetry.error(failure)
    return said(failure.messageOrName())
}

/** [message] the way [unexpected] says it, for a failure that has been counted already. */
fun said(message: String): String {
    val said = message.trimEnd('.', ' ')
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
    Tag.inserting(
        Component.text(
            if (forms.isEmpty()) value.toString() else forms[pluralForm(
                value.toLong(),
                forms.size
            )]
        )
    )
}
