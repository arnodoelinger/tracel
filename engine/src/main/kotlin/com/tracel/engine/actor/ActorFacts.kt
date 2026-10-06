package com.tracel.engine.actor

import com.tracel.model.world.entity.EntityTypeKey
import java.util.*

/**
 * What the log cannot say about who did a thing: a mob's type and a player's game mode.
 *
 * A mob's type is written once, when the mob first gets an ID. A player's mode is a timeline, one row per switch.
 */
public interface ActorFacts {
    /** Notes that [player] was in [mode] from [millis] on, unless that is the mode they were already in. */
    public suspend fun noteMode(player: UUID, mode: Int, millis: Long)

    /** Notes that [player] opened a container at [millis]; the visit stays open until [closeVisit]. */
    public suspend fun openVisit(player: UUID, millis: Long)

    /** Notes that [player] let go of the container they had open, if they had one. */
    public suspend fun closeVisit(player: UUID, millis: Long)

    /** The container visits of each of [players]; a player with none is left out. */
    public suspend fun visitsOf(players: Collection<UUID>): Map<UUID, VisitTimeline>

    /** The mode of each of [players] over time; a player with no rows is left out. */
    public suspend fun modesOf(players: Collection<UUID>): Map<UUID, ModeTimeline>

    /** The type of each of [entities] that was written down; the rest are left out. */
    public suspend fun kindsOf(entities: Collection<UUID>): Map<UUID, EntityTypeKey>
}

/** What game mode (as the code it was noted with) a player was in at any moment. */
public class ModeTimeline(private val since: NavigableMap<Long, Int>) {
    /** @return the mode at [millis], or `null` if nothing was known about the player by then. */
    public fun at(millis: Long): Int? = since.floorEntry(millis)?.value
}

/**
 * When a player had a container open. Everything they move while one is open is one visit, and lookup shows the
 * visit's result, not every click.
 */
public class VisitTimeline(private val opened: NavigableMap<Long, Long>) {
    /** @return when the visit [millis] fell in began, if any; it names the visit. */
    public fun at(millis: Long): Long? {
        val visit = opened.floorEntry(millis) ?: return null
        return visit.key.takeIf { visit.value == OPEN || millis <= visit.value + SLACK_MILLIS }
    }

    public companion object {
        public const val OPEN: Long = Long.MAX_VALUE
        public const val SLACK_MILLIS: Long = 1_000L
    }
}
