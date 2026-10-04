package com.tracel.plugin.command.presenter

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.annotations.isBookkeeping
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.namesMaterial
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.plugin.command.presenter.support.Glyphs
import com.tracel.plugin.i18n.lower
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.GameMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/** One line per recorded change: lookup, the inspector and rollback previews all read the same. */
internal object ChangeLinePresenter {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Creates a component representing the change. */
    fun of(change: WorldChange, actors: Actors = Actors.NONE): Component {
        val who = change.causedBy?.let { holder(it, actors) } ?: Component.text(change.cause.name.lowercase())
        val at = at(change.at.x, change.at.y, change.at.z)
        return when (val subject = change.subject) {
            is ChangeSubject.Block -> {
                val trampled = trampled(subject)
                if (trampled != null) tr("change.trampled", "who" to who, "block" to trampled, "at" to at)
                else tr(
                    "change.block",
                    "who" to who,
                    "verb" to verb(change.action),
                    "block" to block(subject),
                    "at" to at
                )
            }

            is ChangeSubject.Entity ->
                tr(
                    "change.entity",
                    "who" to who,
                    "verb" to (leash(subject) ?: verb(change.action)),
                    "entity" to NamePresenter.entity(subject.type.value),
                    "at" to at
                )
        }
    }

    /** A block that only became another block, with no name for it in [TransitionPresenter]: crops, doors, redstone. Noise. */
    fun isUnnamedChange(change: WorldChange): Boolean {
        val subject = change.subject as? ChangeSubject.Block ?: return false
        if (change.action == ActionKind.BLOCK_GROW) return false
        if (subject.before.isAirLike || subject.after.isAirLike) return false
        val byEntity = change.cause == CauseKind.ENTITY_ACTION || change.causedBy is HolderId.Entity
        return TransitionPresenter.of(subject.before.data.value, subject.after.data.value, byEntity) == null
    }

    /** A line of the log, block, or item, before the lines that repeat are folded into one. */
    class Logged(
        val millis: Long,
        val key: Any,
        val mark: Component,
        val who: Component,
        val verb: Component,
        val whoText: String,
        val whatText: (total: Long) -> String,
        val title: String,
        val what: (total: Long) -> Component,
        val quantity: Long = 1,
        val from: Component? = null,
        val to: Component? = null,
        val at: BlockPos? = null,
        val mode: GameMode? = null,
        val net: Net? = null,
        val visit: Long? = null,
        val rolledAt: Long? = null,
        val counted: Boolean = true,
    )

    /** [delta] is what this line did to the side [family] counts for: positive for [ItemPresenter.Family.plus]. */
    class Net(val family: ItemPresenter.Family, val delta: Long, val item: Component, val material: String)

    /** The line of the log for [change]: what happened, who did it, when and where. */
    @Unstable
    fun logged(change: WorldChange, actors: Actors, rolled: Map<Long, Long> = emptyMap()): Logged {
        val rolledAt = rolled[change.seq.raw]
        val phrase = phrase(change)
        val (from, to) = when (val subject = change.subject) {
            is ChangeSubject.Block -> id(subject.before) to id(subject.after)
            is ChangeSubject.Entity -> subject.type.value to ""
        }
        return Logged(
            millis = change.epochMillis,
            key = listOf(change.causedBy ?: change.cause, change.cause, change.action, from, to, rolledAt != null),
            mark = mark(change.action),
            who = who(change.causedBy, change.cause, actors),
            verb = phrase.verb,
            whoText = whoText(change.causedBy, change.cause, actors),
            whatText = { phrase.whatText },
            title = phrase.title,
            what = { phrase.what },
            from = phrase.from,
            to = phrase.to,
            at = change.at,
            mode = modeOf(change.causedBy, change.epochMillis, actors),
            rolledAt = rolledAt,
            counted = !isSecondHalf(change),
        )
    }

    /** The line of the log for [event]: who said, typed, joined, or disconnected. */
    fun logged(event: ActorEvent, actors: Actors): Logged {
        val verb = when (event.kind) {
            EventKind.CHAT -> "said"
            EventKind.COMMAND -> "ran"
            EventKind.JOIN -> "joined"
            EventKind.QUIT -> "left"
            EventKind.DEATH -> "died"
        }
        val text = event.text.orEmpty()
        return Logged(
            millis = event.epochMillis,
            key = listOf(event.by, event.kind, text),
            mark = when (event.kind) {
                EventKind.JOIN -> ItemPresenter.PLUS
                EventKind.QUIT, EventKind.DEATH -> ItemPresenter.MINUS
                else -> tr("common.mark.said")
            },
            who = who(event.by, CauseKind.PLAYER_ACTION, actors),
            verb = lower("lookup.verb.$verb"),
            whoText = whoText(event.by, CauseKind.PLAYER_ACTION, actors),
            whatText = { text },
            title = verb,
            what = { Component.text(text) },
            at = event.at,
            mode = modeOf(event.by, event.epochMillis, actors),
        )
    }

    /** The item lines of [transaction]: what was put, taken, dropped, crafted. */
    fun logged(
        transaction: Transaction,
        item: String?,
        everything: Boolean,
        actors: Actors,
        rolled: Map<Long, Long> = emptyMap(),
    ): List<Logged> {
        val rolledAt = rolled[transaction.seq.raw]
        if (transaction.cause.isBookkeeping || (transaction.cause == CauseKind.WEAR && !everything)) return emptyList()
        val unseen = transaction.flows.filter { mint ->
            mint.kind == FlowKind.MINT && mint.destination is HolderId.Player && transaction.flows.any { out ->
                out !== mint && out.source == mint.destination && out.itemKey == mint.itemKey &&
                        out.quantity.raw >= mint.quantity.raw
            }
        }
        return transaction.flows.mapNotNull { flow ->
            if (item != null && !flow.itemKey.material.namesMaterial(item)) return@mapNotNull null
            if (unseen.any { it === flow }) return@mapNotNull null
            val act = (if (transaction.cause == CauseKind.WEAR) ItemPresenter.Act(
                "damaged",
                ItemPresenter.BOTH
            ) else ItemPresenter.of(flow, everything))
                ?: return@mapNotNull null
            val doer =
                transaction.causedBy ?: listOf(flow.source, flow.destination).firstOrNull { it is HolderId.Player }
            val place = act.place?.let { it as? HolderId.Block }
            val name = NamePresenter.of(flow.itemKey.material)
            val family = ItemPresenter.family(act.name)
            val visit = if (family?.container == true) (doer as? HolderId.Player)?.let {
                actors.visit(
                    it.uuid,
                    transaction.epochMillis
                )
            } else null
            val delta =
                if (family == null) 0 else if (act.name == family.plus) flow.quantity.raw else -flow.quantity.raw
            Logged(
                millis = transaction.epochMillis,
                key = if (visit != null) listOf(doer, "visit", visit, flow.itemKey.material, rolledAt != null) else listOf(
                    doer ?: transaction.cause, family?.name ?: act.name, flow.itemKey.material, place, rolledAt != null
                ),
                mark = act.mark,
                who = who(doer, transaction.cause, actors),
                verb = lower("lookup.verb.${act.name}"),
                whoText = whoText(doer, transaction.cause, actors),
                whatText = { total -> "$total ${NamePresenter.pretty(flow.itemKey.material)}" },
                title = act.name,
                what = { total -> quantity(total, name) },
                quantity = flow.quantity.raw,
                at = transaction.at ?: place?.let { BlockPos(it.world, it.x, it.y, it.z) },
                mode = modeOf(doer, transaction.epochMillis, actors),
                net = family?.let { Net(it, delta, name, flow.itemKey.material) },
                visit = visit,
                rolledAt = rolledAt,
            )
        }
    }

    /** [first] and every record that repeats it, folded: newest shown, the count and the total quantity kept. */
    class Stack(val first: Logged) {
        var group: Long = first.millis
        var count: Int = if (first.counted) 1 else 0; private set
        var quantity: Long = first.quantity; private set
        var oldest: Long = first.millis; private set

        var net: Long = first.net?.delta ?: 0; private set
        var gained: Long = first.net?.delta?.takeIf { it > 0 } ?: 0; private set
        var lost: Long = first.net?.delta?.takeIf { it < 0 }?.let { -it } ?: 0; private set

        /** Adds a logged entry to the stack. */
        fun add(line: Logged) {
            if (line.counted) count++
            quantity += line.quantity
            oldest = minOf(oldest, line.millis)
            val delta = line.net?.delta ?: return
            net += delta
            if (delta > 0) gained += delta else lost -= delta
        }
    }

    /** [stacks] as a text file: what the pages show, with the whole time instead of how long ago. */
    fun document(stacks: List<Stack>, command: String, nowMillis: Long, cap: Int): String {
        val shown = stacks.take(cap)
        val head = buildList {
            add("Tracel lookup${if (command.isEmpty()) "" else ": /tracel $command"}")
            add("Made ${STAMP.format(Instant.ofEpochMilli(nowMillis))}, ${stacks.size} lines, newest first")
            add("")
        }
        val tail = if (stacks.size > shown.size) listOf(
            "",
            "... and ${stacks.size - shown.size} older lines more"
        ) else emptyList()
        return (head + shown.map(::plain) + tail).joinToString("\n")
    }

    /** The lookup row for [stack]: a repeat shows once, with a count. */
    fun row(stack: Stack, nowMillis: Long): Component {
        val entry = stack.first
        val ago = ago(nowMillis - entry.millis)
        val times = if (stack.count > 1) tr("lookup.times", "n" to superscript(stack.count)) else Component.empty()
        val at = entry.at
        val net = entry.net
        val verbName = netVerb(stack)
        val mark = when {
            net == null -> entry.mark
            stack.net > 0 -> ItemPresenter.PLUS
            stack.net < 0 -> ItemPresenter.MINUS
            else -> ItemPresenter.BOTH
        }
        val hover = buildList {
            if (entry.from != null) add(tr("lookup.hover.turned", "from" to entry.from, "to" to entry.to))
            if (stack.count > 1 && net != null) {
                add(
                    tr(
                        "lookup.net",
                        "plus" to tr("lookup.verb.${net.family.plus}"),
                        "gained" to stack.gained,
                        "minus" to lower("lookup.verb.${net.family.minus}"),
                        "lost" to stack.lost,
                        "first" to ago(nowMillis - stack.oldest),
                        "last" to ago,
                    )
                )
            } else if (stack.count > 1) {
                add(
                    tr(
                        "lookup.title",
                        "verb" to tr("lookup.verb.${entry.title}"),
                        "n" to stack.count,
                        "first" to ago(nowMillis - stack.oldest),
                        "last" to ago
                    )
                )
            }
            add(tr("lookup.hover.time", "time" to STAMP.format(Instant.ofEpochMilli(entry.millis))))
            entry.rolledAt?.let {
                add(tr("lookup.hover.rolled_back", "time" to STAMP.format(Instant.ofEpochMilli(it)), "ago" to ago(nowMillis - it)))
            }
            if (at != null) add(tr("lookup.hover.coords", "at" to "${at.x}, ${at.y}, ${at.z}"))
            entry.mode?.let { add(tr("lookup.hover.mode", "mode" to tr("lookup.mode.${it.name.lowercase()}"))) }
            if (at != null) {
                add(Component.empty())
                add(tr("lookup.hover.teleport"))
            }
        }
        val verb = verbName?.let { lower("lookup.verb.$it") } ?: entry.verb
        val total =
            if (net == null) stack.quantity else if (stack.net != 0L) kotlin.math.abs(stack.net) else stack.gained
        val full = if (net == null) entry.what(stack.quantity) else netted(net, stack)
        val label = if (net == null) entry.whatText(stack.quantity) else "$total ${NamePresenter.pretty(net.material)}"
        val suffix = if (stack.count > 1) " ${superscript(stack.count)}⋆" else ""
        val fixed = Glyphs.width("${english(ago)} X ${entry.whoText} ${english(verb)} ") + Glyphs.width(suffix)
        val clipped = Glyphs.width(label) > Glyphs.LINE - fixed
        val what = if (!clipped) full else Component.text(Glyphs.clip(label, Glyphs.LINE - fixed))
        val shown =
            if (!clipped) hover else listOf(Component.text(label, NamedTextColor.WHITE), Component.empty()) + hover
        val row = Component.text()
            .append(ago.colorIfAbsent(NamedTextColor.GRAY)).append(Component.space())
            .append(mark).append(Component.space())
            .append(
                Component.text()
                    .append(entry.who).append(Component.space())
                    .append(verb.colorIfAbsent(NamedTextColor.GRAY)).append(Component.space())
                    .append(what).append(times)
                    .build()
                    .decoration(TextDecoration.STRIKETHROUGH, entry.rolledAt != null)
            )
            .build()
            .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), shown)))
        return if (at == null) row else row.clickEvent(ClickEvent.runCommand("/tracel tp ${at.world.uuid} ${at.x} ${at.y} ${at.z}"))
    }

    /** @return the display component for a given holder ID. */
    fun holder(holder: HolderId, actors: Actors = Actors.NONE): Component = when (holder) {
        is HolderId.Block -> tr("holder.block", "x" to holder.x, "y" to holder.y, "z" to holder.z)
        is HolderId.PlacedBlock -> tr("holder.block", "x" to holder.x, "y" to holder.y, "z" to holder.z)
        is HolderId.Player -> tr("holder.player", "name" to playerName(holder.uuid))
        is HolderId.Entity -> entity(holder.uuid, actors)
        is HolderId.PlacedEntity -> tr("holder.placed_entity", "uuid" to holder.uuid)
        else -> Component.text(holder.toString())
    }

    /** @return the position string for the given coordinates. */
    fun at(x: Int, y: Int, z: Int): String = "$x, $y, $z"

    private fun modeOf(by: HolderId?, millis: Long, actors: Actors): GameMode? =
        (by as? HolderId.Player)?.let { actors.mode(it.uuid, millis) }

    private fun whoText(by: HolderId?, cause: CauseKind, actors: Actors): String = when (by) {
        is HolderId.Player -> playerName(by.uuid)
        is HolderId.Entity -> actors.kind(by.uuid)?.let(NamePresenter::pretty) ?: english(who(by, cause, actors))
        else -> english(who(by, cause, actors))
    }

    private fun english(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(component, Locale.ENGLISH))

    private fun who(by: HolderId?, cause: CauseKind, actors: Actors): Component =
        (by as? HolderId.Player)?.let { Component.text(playerName(it.uuid)) }
            ?: by?.let { holder(it, actors) } ?: Component.text(cause.name.lowercase())

    private fun quantity(total: Long, item: Component): Component =
        Component.textOfChildren(Component.text(total), Component.space(), item)

    private fun netted(net: Net, stack: Stack): Component {
        val total = if (stack.net != 0L) kotlin.math.abs(stack.net) else stack.gained
        return quantity(total, net.item)
    }

    private fun netVerb(stack: Stack): String? =
        stack.first.net?.let { if (stack.net > 0) it.family.plus else if (stack.net < 0) it.family.minus else "shuffled" }

    private fun plain(stack: Stack): String {
        val entry = stack.first
        val net = entry.net
        val verb = netVerb(stack)?.let { english(lower("lookup.verb.$it")) } ?: english(entry.verb)
        val what = if (net == null) entry.whatText(stack.quantity)
        else "${if (stack.net != 0L) kotlin.math.abs(stack.net) else stack.gained} ${NamePresenter.pretty(net.material)}"
        val repeat =
            if (stack.count > 1) " (x${stack.count} since ${STAMP.format(Instant.ofEpochMilli(stack.oldest))})" else ""
        val place = entry.at?.let { " at ${it.x},${it.y},${it.z}" }.orEmpty()
        return "${STAMP.format(Instant.ofEpochMilli(entry.millis))} ${entry.whoText} $verb $what$repeat$place"
    }

    private class Phrase(
        val verb: Component,
        val title: String,
        val what: Component,
        val whatText: String,
        val from: Component? = null,
        val to: Component? = null,
    )

    private fun phrase(change: WorldChange): Phrase = when (val subject = change.subject) {
        is ChangeSubject.Entity -> {
            val name = leashName(subject) ?: verbName(change.action)
            Phrase(
                lower("lookup.verb.$name"),
                name,
                NamePresenter.entity(subject.type.value),
                NamePresenter.pretty(subject.type.value)
            )
        }

        is ChangeSubject.Block -> {
            val before = id(subject.before)
            val after = id(subject.after)
            val byEntity = change.cause == CauseKind.ENTITY_ACTION || change.causedBy is HolderId.Entity
            val named = TransitionPresenter.of(subject.before.data.value, subject.after.data.value, byEntity)
            val from = NamePresenter.of(before)
            val to = NamePresenter.of(after)
            val plain = verbName(change.action)
            when {
                // What bone meal grew reads the same whether it was a flower or a crop going up a stage
                change.action == ActionKind.BLOCK_CLICK || change.action == ActionKind.BLOCK_GROW -> Phrase(
                    lower("lookup.verb.$plain"),
                    plain,
                    to,
                    NamePresenter.pretty(after)
                )

                subject.after.isAirLike -> Phrase(
                    lower("lookup.verb.$plain"),
                    plain,
                    from,
                    NamePresenter.pretty(before)
                )

                subject.before.isAirLike -> Phrase(lower("lookup.verb.$plain"), plain, to, NamePresenter.pretty(after))
                named != null -> Phrase(
                    lower("lookup.verb.${named.key}"),
                    named.key,
                    if (named.result) to else from,
                    NamePresenter.pretty(if (named.result) after else before)
                )

                before == after -> Phrase(lower("lookup.verb.updated"), "updated", to, NamePresenter.pretty(after))
                else -> Phrase(lower("lookup.verb.became"), "changed", to, NamePresenter.pretty(after), from, to)
            }
        }
    }

    private fun isSecondHalf(change: WorldChange): Boolean {
        if (change.action != ActionKind.BLOCK_PLACE) return false
        val after = (change.subject as? ChangeSubject.Block)?.after?.data?.value ?: return false
        return "part=head" in after || "half=upper" in after
    }

    private fun id(shape: BlockShape): String = shape.data.value.substringBefore('[')

    private fun superscript(n: Int): String = n.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")

    private fun mark(action: ActionKind): Component = when (action) {
        ActionKind.BLOCK_PLACE, ActionKind.ENTITY_SPAWN, ActionKind.BLOCK_GROW -> ItemPresenter.PLUS
        ActionKind.BLOCK_BREAK, ActionKind.ENTITY_REMOVE -> ItemPresenter.MINUS
        else -> ItemPresenter.BOTH
    }

    private fun ago(millis: Long): Component {
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

    private fun entity(uuid: UUID, actors: Actors): Component {
        val kind = actors.kind(uuid)
        val name = if (kind != null) NamePresenter.entity(kind) else tr("holder.entity_gone")
        return name.hoverEvent(HoverEvent.showText(Component.text(uuid.toString())))
    }

    private fun verb(action: ActionKind): Component = lower("lookup.verb.${verbName(action)}")

    private fun verbName(action: ActionKind): String = when (action) {
        ActionKind.BLOCK_PLACE -> "placed"
        ActionKind.BLOCK_BREAK -> "broke"
        ActionKind.BLOCK_CHANGE -> "changed"
        ActionKind.SIGN_EDIT -> "edited"
        ActionKind.ENTITY_SPAWN -> "spawned"
        ActionKind.ENTITY_REMOVE -> "removed"
        ActionKind.ENTITY_CHANGE -> "altered"
        ActionKind.BLOCK_CLICK -> "clicked"
        ActionKind.BLOCK_GROW -> "grew"
    }

    private fun leashName(subject: ChangeSubject.Entity): String? {
        val before = subject.before?.extras.leashHolder
        val after = subject.after?.extras.leashHolder
        return when {
            before == null && after != null -> "leashed"
            before != null && after == null -> "unleashed"
            else -> null
        }
    }

    private fun leash(subject: ChangeSubject.Entity): Component? = leashName(subject)?.let { lower("lookup.verb.$it") }

    private fun trampled(subject: ChangeSubject.Block): Component? {
        val before = subject.before.data.value.substringBefore('[')
        val after = subject.after.data.value.substringBefore('[')
        val trampled = (before.endsWith(":farmland") && after.endsWith(":dirt")) || before.endsWith(":turtle_egg")
        return if (trampled) NamePresenter.of(before) else null
    }

    private fun block(subject: ChangeSubject.Block): Component {
        val before = NamePresenter.of(subject.before.data.value)
        val after = NamePresenter.of(subject.after.data.value)
        return when {
            subject.after.isAirLike -> before
            subject.before.isAirLike -> after
            else -> tr("change.turned", "from" to before, "to" to after)
        }
    }

    private fun playerName(uuid: UUID): String =
        runCatching { Bukkit.getOfflinePlayer(uuid).name }.getOrNull() ?: uuid.toString()
}
