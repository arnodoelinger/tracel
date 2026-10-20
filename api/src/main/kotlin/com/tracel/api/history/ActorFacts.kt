package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.world.NamespacedName
import java.time.Instant
import java.util.UUID
import org.bukkit.GameMode

/**
 * What history knows about the players and mobs in the records it has been shown.
 *
 * Starts empty.
 */
@Unstable
public interface ActorFacts {
    /** Reads what is not known yet about the players and mobs among the actors and holders of [records]. */
    public suspend fun learn(records: Collection<HistoryRecord>)

    /** The type of mob [uuid], if history knows it. */
    public fun entityType(uuid: UUID): NamespacedName?

    /** The mode [player] was in at [at], if history knows it. */
    public fun mode(player: UUID, at: Instant): GameMode?

    /** When the container visit [player] was in at [at] began, if they were in one. */
    public fun visitStart(player: UUID, at: Instant): Instant?
}
