package com.tracel.plugin.command.presenter.line

import com.tracel.annotations.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.history.ActionKind
import com.tracel.api.history.ActorFacts
import com.tracel.api.history.BlockRecord
import com.tracel.api.history.CauseKind
import com.tracel.api.history.EntityRecord
import com.tracel.api.history.EventKind
import com.tracel.api.history.EventRecord
import com.tracel.api.history.FlowKind
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.item.ItemHolder
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.item.namesMaterial
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.command.presenter.ItemPresenter
import com.tracel.plugin.api.read.Actors
import com.tracel.plugin.command.presenter.NamePresenter
import com.tracel.plugin.command.presenter.support.Glyphs
import com.tracel.plugin.i18n.lower
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.specifics.block.AirBlock
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
    private val HIT = Regex("""^(.+?)(?: with (\S+))? \(([\d.]+) hp(?:, ([\d.]+) left)?\)$""")
    private val BEACON = Regex("""^beacon (\S+) (\S+)$""")
    private val COMPASS = Regex("""^compass (\S+)$""")
    private val STAND = Regex("""^stand (\S+) (\S+) (\S+)$""")
    private val FRAME = Regex("""^frame (put|took|rotated) (\S+)$""")
    private val TRADE = Regex("""^(\S+) x(\d+) for (.+) with (\S+)$""")
    private val PRICE = Regex("""^(\S+) x(\d+)$""")

    private enum class Half { FIRST, SECOND }

    /** A change kept for rollback only. */
    fun isKeptOnly(record: EntityRecord): Boolean =
        record.action == ActionKind.ENTITY_CHANGE && leashName(record) == null

    /** A block that only became another block, with no name for it in [TransitionPresenter]: crops, doors, redstone. Noise. */
    fun isUnnamedChange(record: BlockRecord): Boolean {
        if (record.action != ActionKind.BLOCK_CHANGE) return false
        if (airLike(record.before) || airLike(record.after)) return false
        return TransitionPresenter.of(record.before, record.after, byEntity(record)) == null
    }

    /** The line of the log for a block [record]: what happened, who did it, when and where. */
    @Unstable
    fun logged(record: BlockRecord, facts: ActorFacts): LoggedLine = line(record, facts, phrase(record), recordKey(record))

    /** The line of the log for an entity [record]: what happened, who did it, when and where. */
    @Unstable
    fun logged(record: EntityRecord, facts: ActorFacts): LoggedLine =
        line(record, facts, phrase(record), recordKey(record))

    private fun recordKey(record: BlockRecord): Pair<String, String> = id(record.before) to id(record.after)

    private fun recordKey(record: EntityRecord): Pair<String, String> = record.type to ""

    private fun line(record: BlockRecord, facts: ActorFacts, phrase: Phrase, ids: Pair<String, String>): LoggedLine {
        val rolledAt = record.rolledBack?.toEpochMilli()
        return LoggedLine(
            millis = record.at.toEpochMilli(),
            key = listOf(
                by(record) ?: record.cause, record.cause, record.action, ids.first, ids.second, rolledAt != null,
                phrase.whatText.takeIf { record.action == ActionKind.SIGN_EDIT },
            ),
            mark = if (phrase.title == "placed") ItemPresenter.PLUS else mark(record.action),
            who = who(by(record), record.cause, facts),
            verb = phrase.verb,
            whoText = whoText(by(record), record.cause, facts),
            whatText = { phrase.whatText },
            title = phrase.title,
            what = { phrase.what },
            from = phrase.from,
            to = phrase.to,
            at = record.place,
            mode = modeOf(by(record), record.at, facts),
            rolledAt = rolledAt,
            counted = halfOf(record) != Half.SECOND,
            pairs = halfOf(record) != null,
        )
    }

    private fun line(record: EntityRecord, facts: ActorFacts, phrase: Phrase, ids: Pair<String, String>): LoggedLine {
        val rolledAt = record.rolledBack?.toEpochMilli()
        return LoggedLine(
            millis = record.at.toEpochMilli(),
            key = listOf(
                by(record) ?: record.cause, record.cause, record.action, ids.first, ids.second, rolledAt != null,
                null,
            ),
            mark = mark(record.action),
            who = who(by(record), record.cause, facts),
            verb = phrase.verb,
            whoText = whoText(by(record), record.cause, facts),
            whatText = { phrase.whatText },
            title = phrase.title,
            what = { phrase.what },
            from = phrase.from,
            to = phrase.to,
            at = record.place,
            mode = modeOf(by(record), record.at, facts),
            rolledAt = rolledAt,
            counted = true,
            pairs = false,
        )
    }

    private fun by(record: HistoryRecord): Actor? = record.actor?.takeUnless { it == Actor.Natural }

    /** The line of the log for [event]: who said, typed, joined, or disconnected. */
    fun logged(event: EventRecord, facts: ActorFacts): LoggedLine {
        val verb = when (event.kind) {
            EventKind.CHAT -> "said"
            EventKind.COMMAND -> "ran"
            EventKind.JOIN -> "joined"
            EventKind.QUIT -> "left"
            EventKind.DEATH -> "died"
            EventKind.SHOOT -> "shot"
            EventKind.HIT -> "hit"
            EventKind.CLICK -> "clicked"
            EventKind.SET -> "set"
            EventKind.PULL -> "pulled"
            EventKind.PUSH -> "pushed"
            EventKind.SLEEP -> "slept"
            EventKind.TRADE -> "traded"
            EventKind.TELEPORT -> "teleported"
            EventKind.CUSTOM -> "did"
        }
        val raw = rawText(event)
        val text = when (event.kind) {
            EventKind.SHOOT, EventKind.PULL, EventKind.PUSH -> NamePresenter.pretty(raw)
            EventKind.HIT -> HIT.matchEntire(raw)?.let { hit(it).text } ?: raw
            EventKind.SLEEP -> NamePresenter.pretty(raw)
            EventKind.DEATH -> capitalized(raw)
            EventKind.SET -> edit(raw)?.first ?: raw
            EventKind.TELEPORT -> NamePresenter.pretty(raw)
            EventKind.TRADE -> TRADE.matchEntire(raw)?.let { deal(it).text } ?: raw
            EventKind.CUSTOM -> english(Extensions.live.eventLine(raw))
            else -> raw
        }
        val shown: Component = when (event.kind) {
            EventKind.SHOOT, EventKind.PULL, EventKind.PUSH -> NamePresenter.entity(raw)
            EventKind.HIT -> HIT.matchEntire(raw)?.let { hit(it).row } ?: Component.text(raw)
            EventKind.CLICK, EventKind.SLEEP -> NamePresenter.of(raw)
            EventKind.TRADE -> TRADE.matchEntire(raw)?.let { deal(it).row } ?: Component.text(raw)
            EventKind.DEATH -> Component.text(capitalized(raw))
            EventKind.SET -> edit(raw)?.second ?: setting(raw)
            EventKind.CUSTOM -> Extensions.live.eventLine(raw)
            else -> Component.text(raw)
        }
        val by = by(event)
        return LoggedLine(
            millis = event.at.toEpochMilli(),
            key = listOf(by, event.kind, text),
            mark = when (event.kind) {
                EventKind.JOIN -> ItemPresenter.PLUS
                EventKind.QUIT, EventKind.DEATH -> ItemPresenter.MINUS
                else -> tr("common.mark.said")
            },
            who = who(by, CauseKind.PLAYER_ACTION, facts),
            verb = lower("lookup.verb.$verb"),
            whoText = whoText(by, CauseKind.PLAYER_ACTION, facts),
            whatText = { text },
            title = verb,
            what = { shown },
            tip = if (event.kind == EventKind.HIT) HIT.matchEntire(raw)?.let { hit(it).tip } else null,
            at = event.place,
            mode = modeOf(by, event.at, facts),
        )
    }

    private fun rawText(event: EventRecord): String {
        val name = event.name
        return if (event.kind == EventKind.CUSTOM && name != null) ApiConversions.customText(name, event.text)
        else event.text.orEmpty()
    }

    /**
     * The item lines of one transaction's [flows], all of one cause, one actor, one moment: what was put, taken,
     * dropped, crafted.
     */
    fun logged(
        flows: List<ItemFlow>,
        item: String?,
        everything: Boolean,
        facts: ActorFacts,
    ): List<LoggedLine> {
        val first = flows.firstOrNull() ?: return emptyList()
        val cause = first.cause
        val rolledAt = first.rolledBack?.toEpochMilli()
        if (cause.isBookkeeping || (cause == CauseKind.WEAR && !everything)) return emptyList()
        val unseen = flows.filter { mint ->
            mint.kind == FlowKind.MINT && isPlayer(mint.to) && flows.any { out ->
                out !== mint && out.from == mint.to && out.item == mint.item && out.amount >= mint.amount
            }
        }
        return flows.mapNotNull { flow ->
            if (item != null && !namesItem(keyOf(flow), item)) return@mapNotNull null
            if (unseen.any { it === flow }) return@mapNotNull null
            val act = (if (cause == CauseKind.WEAR) ItemPresenter.Act(
                "damaged",
                ItemPresenter.BOTH
            ) else ItemPresenter.of(flow, everything, byMachine(cause)))
                ?: return@mapNotNull null
            val doer = by(flow) ?: listOf(flow.from, flow.to).firstOrNull(::isPlayer)?.let {
                Actor.Player((it as ItemHolder.Player).uuid)
            }
            val place = act.place as? ItemHolder.Container
            val id = itemId(flow)
            val name = NamePresenter.of(id)
            val family = ItemPresenter.family(act.name)
            val visit = if (family?.container == true) (doer as? Actor.Player)?.let {
                facts.visitStart(it.uuid, flow.at)?.toEpochMilli()
            } else null
            val delta =
                if (family == null) 0 else if (act.name == family.plus) flow.amount else -flow.amount
            LoggedLine(
                millis = flow.at.toEpochMilli(),
                key = if (visit != null) listOf(
                    doer,
                    "visit",
                    visit,
                    id,
                    rolledAt != null
                ) else listOf(
                    doer ?: cause, family?.name ?: act.name, id, place, rolledAt != null
                ),
                mark = act.mark,
                who = who(doer, cause, facts),
                verb = lower("lookup.verb.${act.name}"),
                whoText = whoText(doer, cause, facts),
                whatText = { total -> "$total ${NamePresenter.pretty(id)}" },
                title = act.name,
                what = { total -> quantity(total, name) },
                quantity = flow.amount,
                at = flow.place ?: place?.place,
                mode = modeOf(doer, flow.at, facts),
                net = family?.let { LineNet(it, delta, name, id) },
                visit = visit,
                rolledAt = rolledAt,
            )
        }
    }

    private fun isPlayer(holder: ItemHolder): Boolean = holder is ItemHolder.Player && !holder.stash

    private fun keyOf(flow: ItemFlow): ItemKey = ItemKey(flow.item.material, flow.item.variant?.let(::ContentHash))

    /** [stacks] as a text file: what the pages show, with the whole time instead of how long ago. */
    fun document(stacks: List<LineStack>, command: String, nowMillis: Long, cap: Int): String {
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
    fun row(stack: LineStack, nowMillis: Long): Component {
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
                add(
                    tr(
                        "lookup.hover.rolled_back",
                        "time" to STAMP.format(Instant.ofEpochMilli(it)),
                        "ago" to ago(nowMillis - it)
                    )
                )
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
            if (!clipped) hover else {
                val head = entry.tip ?: Component.text(label, NamedTextColor.WHITE)
                if (entry.from != null) listOf(head) + hover else listOf(head, Component.empty()) + hover
            }
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
        return if (at == null) row else row.clickEvent(ClickEvent.runCommand("/tracel tp ${at.world} ${at.x} ${at.y} ${at.z}"))
    }

    /** @return the display component for a given holder ID. */
    fun holder(holder: HolderId, facts: ActorFacts = Actors.NONE): Component = when (holder) {
        is HolderId.Block -> tr("holder.block", "x" to holder.x, "y" to holder.y, "z" to holder.z)
        is HolderId.PlacedBlock -> tr("holder.block", "x" to holder.x, "y" to holder.y, "z" to holder.z)
        is HolderId.Player -> tr("holder.player", "name" to playerName(holder.uuid))
        is HolderId.Entity -> entity(holder.uuid, facts)
        is HolderId.PlacedEntity -> tr("holder.placed_entity", "uuid" to holder.uuid)
        is HolderId.Plugin -> tr("holder.plugin", "name" to holder.name)
        is HolderId.Custom -> Component.text(Extensions.live.name(holder))
        else -> Component.text(holder.toString())
    }

    /** @return the position string for the given coordinates. */
    fun at(x: Int, y: Int, z: Int): String = "$x, $y, $z"

    private fun setting(raw: String): Component {
        fun effect(id: String) = if (id == "none") tr("lookup.none") else NamePresenter.effect(id)
        BEACON.matchEntire(raw)?.let { found ->
            val (primary, secondary) = found.destructured
            return Component.empty()
                .append(NamePresenter.of("beacon"))
                .append(Component.text(": "))
                .append(effect(primary))
                .append(Component.text(", "))
                .append(effect(secondary))
        }
        COMPASS.matchEntire(raw)?.let { found ->
            val (target) = found.destructured
            return Component.empty()
                .append(NamePresenter.of("compass"))
                .append(Component.text(": "))
                .append(NamePresenter.of(target))
        }
        return Component.text(raw)
    }

    private fun capitalized(raw: String): String =
        raw.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

    private class Blow(val text: String, val row: Component, val tip: Component)

    private fun edit(raw: String): Pair<String, Component>? {
        FRAME.matchEntire(raw)?.let { found ->
            val (what, item) = found.destructured
            val frame = NamePresenter.entity("item_frame")
            val words = NamePresenter.pretty("item_frame")
            val component = Component.empty().append(frame).append(Component.text(": "))
                .append(tr("lookup.edit.frame_$what", "item" to NamePresenter.of(item)))
            return "$words: $what ${NamePresenter.pretty(item)}" to component
        }
        STAND.matchEntire(raw)?.let { found ->
            val (slot, put, took) = found.destructured
            val where = tr("lookup.slot.$slot")
            val parts = buildList {
                if (put != "none") add(tr("lookup.edit.put", "item" to NamePresenter.of(put), "slot" to where))
                if (took != "none") add(tr("lookup.edit.took", "item" to NamePresenter.of(took), "slot" to where))
            }
            val component = Component.empty().append(NamePresenter.entity("armor_stand")).append(Component.text(": "))
                .append(Component.join(JoinConfiguration.separator(Component.text(", ")), parts))
            val plain = buildList {
                if (put != "none") add("put ${NamePresenter.pretty(put)} on $slot")
                if (took != "none") add("took ${NamePresenter.pretty(took)} from $slot")
            }.joinToString(", ")
            return "Armor Stand: $plain" to component
        }
        return null
    }

    private class Deal(val text: String, val row: Component)

    private fun deal(found: MatchResult): Deal {
        val (item, amount, price, trader) = found.destructured
        val paid = price.split(", ").mapNotNull(PRICE::matchEntire)
        val text = buildString {
            append(NamePresenter.pretty(item)).append(" x").append(amount).append(" for ")
            append(paid.joinToString(", ") { "${NamePresenter.pretty(it.groupValues[1])} x${it.groupValues[2]}" })
            append(" with ").append(NamePresenter.pretty(trader))
        }
        var row = Component.empty()
            .append(NamePresenter.of(item))
            .append(Component.text(" x$amount "))
            .append(tr("lookup.deal.for"))
            .append(Component.space())
        paid.forEachIndexed { index, part ->
            if (index > 0) row = row.append(Component.text(", "))
            row = row.append(NamePresenter.of(part.groupValues[1])).append(Component.text(" x${part.groupValues[2]}"))
        }
        row = row.append(Component.space())
            .append(tr("lookup.deal.with"))
            .append(Component.space())
            .append(NamePresenter.entity(trader))
        return Deal(text, row)
    }

    private fun byMachine(cause: CauseKind): Boolean =
        cause == CauseKind.HOPPER || cause == CauseKind.DROPPER || cause == CauseKind.DISPENSER ||
                cause == CauseKind.MACHINE

    private fun hit(found: MatchResult): Blow {
        val (victim, projectile, damage, remaining) = found.destructured
        val hp = if (remaining.isEmpty()) "$damage hp" else "$damage hp, $remaining left"
        val player = victim.startsWith("@")
        val name: Component = if (player) Component.text(victim.drop(1)) else NamePresenter.entity(victim)
        val detail = listOfNotNull(projectile.takeIf(String::isNotEmpty)?.let(NamePresenter::pretty), hp)
        val tail = if (projectile.isEmpty()) Component.text("$hp)")
        else Component.textOfChildren(NamePresenter.entity(projectile), Component.text(", $hp)"))
        val plain = (if (player) victim.drop(1) else NamePresenter.pretty(victim)) + " (${detail.joinToString(", ")})"
        return Blow(
            plain,
            Component.textOfChildren(name, Component.text(" ("), tail),
            Component.textOfChildren(
                name.colorIfAbsent(NamedTextColor.WHITE),
                Component.text(" (", NamedTextColor.GRAY),
                tail.color(NamedTextColor.GRAY),
            ),
        )
    }

    private fun modeOf(by: Actor?, at: Instant, facts: ActorFacts): GameMode? =
        (by as? Actor.Player)?.let { facts.mode(it.uuid, at) }

    private fun whoText(by: Actor?, cause: CauseKind, facts: ActorFacts): String = when (by) {
        is Actor.Player -> playerName(by.uuid) + if (cause == CauseKind.PROJECTILE) " (projectile)" else ""
        is Actor.Entity -> facts.entityType(by.uuid)?.toString()?.let(NamePresenter::pretty)
            ?: english(who(by, cause, facts))

        else -> english(who(by, cause, facts))
    }

    private fun english(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(component, Locale.ENGLISH))

    private fun who(by: Actor?, cause: CauseKind, facts: ActorFacts): Component =
        (by as? Actor.Player)?.let { player ->
            val name = Component.text(playerName(player.uuid))
            if (cause == CauseKind.PROJECTILE) Component.textOfChildren(name, lower("common.via.projectile")) else name
        }
            ?: by?.let { actor(it, facts) } ?: Component.text(NamePresenter.pretty(cause.name))

    private fun actor(actor: Actor, facts: ActorFacts): Component = when (actor) {
        is Actor.Player -> tr("holder.player", "name" to playerName(actor.uuid))
        is Actor.Entity -> entity(actor.uuid, facts)
        is Actor.Block -> tr("holder.block", "x" to actor.place.x, "y" to actor.place.y, "z" to actor.place.z)
        is Actor.Plugin -> tr("holder.plugin", "name" to actor.name)
        is Actor.Addon -> Component.text(Extensions.live.name(HolderId.Custom(actor.kind.toString(), actor.id)))
        Actor.Natural -> Component.empty()
    }

    private fun quantity(total: Long, item: Component): Component =
        Component.textOfChildren(Component.text(total), Component.space(), item)

    private fun netted(net: LineNet, stack: LineStack): Component {
        val total = if (stack.net != 0L) kotlin.math.abs(stack.net) else stack.gained
        return quantity(total, net.item)
    }

    private fun netVerb(stack: LineStack): String? =
        stack.first.net?.let { if (stack.net > 0) it.family.plus else if (stack.net < 0) it.family.minus else "shuffled" }

    private fun plain(stack: LineStack): String {
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

    private fun phrase(record: EntityRecord): Phrase {
        val blown = record.action == ActionKind.ENTITY_REMOVE && record.cause == CauseKind.EXPLOSION
        val name = leashName(record) ?: if (blown) "blown" else verbName(record.action)
        return Phrase(
            lower("lookup.verb.$name"),
            name,
            NamePresenter.entity(record.type),
            NamePresenter.pretty(record.type)
        )
    }

    private fun phrase(record: BlockRecord): Phrase {
        val before = id(record.before)
        val after = id(record.after)
        val named = TransitionPresenter.of(record.before, record.after, byEntity(record))
        val from = NamePresenter.of(before)
        val to = NamePresenter.of(after)
        val plain = verbName(record.action)
        return when {
            // What bone meal grew reads the same whether it was a flower or a crop going up a stage
            record.action == ActionKind.BLOCK_CLICK || record.action == ActionKind.BLOCK_GROW -> Phrase(
                lower("lookup.verb.$plain"),
                plain,
                to,
                NamePresenter.pretty(after)
            )

            record.action == ActionKind.SIGN_EDIT -> sign(record, to, after)

            record.action == ActionKind.BLOCK_PLACE -> Phrase(
                lower("lookup.verb.$plain"),
                plain,
                to,
                NamePresenter.pretty(after)
            )

            airLike(record.after) -> Phrase(
                lower("lookup.verb.$plain"),
                plain,
                from,
                NamePresenter.pretty(before)
            )

            airLike(record.before) -> Phrase(lower("lookup.verb.placed"), "placed", to, NamePresenter.pretty(after))
            named != null -> Phrase(
                lower("lookup.verb.${named.key}"),
                named.key,
                if (named.result) to else from,
                NamePresenter.pretty(if (named.result) after else before)
            )

            before == after -> Phrase(lower("lookup.verb.updated"), "updated", to, NamePresenter.pretty(after))
            else -> Phrase(lower("lookup.verb.placed"), "placed", to, NamePresenter.pretty(after), from, to)
        }
    }

    private fun byEntity(record: BlockRecord): Boolean =
        record.cause == CauseKind.ENTITY_ACTION || record.actor is Actor.Entity

    private fun sign(record: BlockRecord, name: Component, after: String): Phrase {
        val was = record.beforeText
        val now = record.afterText
        val shown = if (now.isNullOrEmpty()) name
        else Component.textOfChildren(name, Component.text(": "), Component.text(now, NamedTextColor.GRAY))
        val plain = NamePresenter.pretty(after) + if (now.isNullOrEmpty()) "" else ": $now"
        return Phrase(
            lower("lookup.verb.edited"),
            "edited",
            shown,
            plain,
            from = Component.text(was?.ifEmpty { "-" } ?: "?"),
            to = Component.text(now?.ifEmpty { "-" } ?: "?"),
        )
    }

    private fun halfOf(record: BlockRecord): Half? {
        if (record.action != ActionKind.BLOCK_PLACE) return null
        val after = record.after
        return when {
            "part=head" in after || "half=upper" in after -> Half.SECOND
            "part=foot" in after || "half=lower" in after -> Half.FIRST
            else -> null
        }
    }

    private fun itemId(flow: ItemFlow): String = Extensions.live.itemName(keyOf(flow)) ?: flow.item.material

    private fun namesItem(key: ItemKey, wanted: String): Boolean =
        key.material.namesMaterial(wanted) || Extensions.live.itemName(key).equals(wanted, ignoreCase = true)

    /** What the block is called: its material, or what a lens sees in it. */
    private fun id(data: String): String = Extensions.live.blockName(data) ?: data.substringBefore('[')

    private fun airLike(data: String): Boolean = AirBlock.entries.any { it.id == data }

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

    private fun entity(uuid: UUID, facts: ActorFacts): Component {
        val kind = facts.entityType(uuid)?.toString()
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

    private fun leashName(record: EntityRecord): String? = when {
        !record.leashedBefore && record.leashedAfter -> "leashed"
        record.leashedBefore && !record.leashedAfter -> "unleashed"
        else -> null
    }

    private fun playerName(uuid: UUID): String =
        runCatching { Bukkit.getOfflinePlayer(uuid).name }.getOrNull() ?: uuid.toString()
}
