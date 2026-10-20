package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemKind
import com.tracel.api.world.LookupArea
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * What a [History] reads. Every field that is set narrows it; build one in the block a history call takes:
 *
 * ```
 * block.history { last(1.days); by(griefer.actor); only(RecordKind.BLOCK) }
 * ```
 *
 * The world's own doings, fire and water and growth, are left out unless [natural] is set.
 */
@Unstable
public data class HistoryQuery(
    public val area: LookupArea? = null,
    public val kinds: Set<RecordKind> = DEFAULT_KINDS,
    public val events: Set<EventKind> = emptySet(),
    public val since: Instant? = null,
    public val until: Instant? = null,
    public val by: Set<Actor> = emptySet(),
    public val notBy: Set<Actor> = emptySet(),
    public val causes: Set<CauseKind> = emptySet(),
    public val notCauses: Set<CauseKind> = emptySet(),
    public val blockCauses: Set<CauseKind>? = null,
    public val actions: Set<ActionKind> = emptySet(),
    public val item: ItemKind? = null,
    public val exactItem: Boolean = false,
    public val natural: Boolean = false,
    public val entity: UUID? = null,
) {
    /** This query, narrowed further by [block]. */
    public fun with(block: Builder.() -> Unit): HistoryQuery = Builder(this).apply(block).query

    /**
     * The receiver of a history block. It only builds a [HistoryQuery];
     * nothing is read until the history is.
     */
    public class Builder(start: HistoryQuery = HistoryQuery()) {
        /** What has been built so far. */
        public var query: HistoryQuery = start; private set

        /** Only what happened at [instant] or later. */
        public fun since(instant: Instant) {
            query = query.copy(since = instant)
        }

        /** Only what happened at [instant] or earlier. */
        public fun until(instant: Instant) {
            query = query.copy(until = instant)
        }

        /** Only the last [duration], counted from now. */
        public fun last(duration: Duration): Unit = last(duration.toJavaDuration())

        /** Only the last [duration], counted from now. */
        public fun last(duration: JavaDuration) {
            since(Instant.now().minus(duration))
        }

        /** Only inside [area]. */
        public fun within(area: LookupArea) {
            query = query.copy(area = area)
        }

        /** Only what one of [actors] did. */
        public fun by(vararg actors: Actor) {
            query = query.copy(by = query.by + actors)
        }

        /** Nothing [actors] did. */
        public fun notBy(vararg actors: Actor) {
            query = query.copy(notBy = query.notBy + actors)
        }

        /** Only for one of [causes]. Naming a natural one reads it even without [natural]. */
        public fun causes(vararg causes: CauseKind) {
            query = query.copy(causes = query.causes + causes)
        }

        /** Nothing for [causes]. */
        public fun exceptCauses(vararg causes: CauseKind) {
            query = query.copy(notCauses = query.notCauses + causes)
        }

        /**
         * Only block and entity records for one of [causes], where that differs from the [causes] item records and
         * events are held to. Left alone, [causes] covers all.
         */
        public fun blockCauses(vararg causes: CauseKind) {
            query = query.copy(blockCauses = causes.toSet())
        }

        /** Only block and entity records that did one of [actions]. */
        public fun actions(vararg actions: ActionKind) {
            query = query.copy(actions = query.actions + actions)
        }

        /** Only blocks and items of [material], any variant: `diamond_ore`, `DIAMOND`. */
        public fun material(material: String) {
            query = query.copy(item = ItemKind.of(material), exactItem = false)
        }

        /** Only items of exactly [kind], the same name and enchantments included. */
        public fun item(kind: ItemKind) {
            query = query.copy(item = kind, exactItem = true)
        }

        /** History reads the world's own doings too: fire, water, growth. */
        public fun natural() {
            query = query.copy(natural = true)
        }

        /** Only records of [kinds]. */
        public fun only(vararg kinds: RecordKind) {
            query = query.copy(kinds = kinds.toSet())
        }

        /** Only what happened to or with the entity [uuid], as far as history ties records to one. */
        public fun entity(uuid: UUID) {
            query = query.copy(entity = uuid)
        }

        /** Events as well, of [kinds] or every kind when none are named. */
        public fun events(vararg kinds: EventKind) {
            query = query.copy(kinds = query.kinds + RecordKind.EVENT, events = query.events + kinds)
        }
    }

    public companion object {
        @JvmField
        public val DEFAULT_KINDS: Set<RecordKind> = setOf(RecordKind.BLOCK, RecordKind.ENTITY, RecordKind.ITEM)
    }
}
