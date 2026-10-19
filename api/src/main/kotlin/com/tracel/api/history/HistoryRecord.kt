package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemHolder
import com.tracel.api.item.ItemKind
import com.tracel.api.world.BlockPlace
import java.time.Instant
import java.util.UUID

/** One thing that happened, as history keeps it. */
@Unstable
public sealed interface HistoryRecord {
    /** When it happened. */
    public val at: Instant

    /** Who did it, or `null` when history does not know. */
    public val actor: Actor?

    /** Why it happened. */
    public val cause: CauseKind

    /** When a rollback took it back, or `null` while it stands. */
    public val rolledBack: Instant?

    /** Where it happened, or `null` for what happened nowhere in particular. */
    public val place: BlockPlace?

    /** Where this record sits in history. A [History] page that starts here starts just past it. */
    public val cursor: HistoryCursor
}

/**
 * A block changing. [before] and [after] are block data as `Bukkit.createBlockData` reads it,
 * `minecraft:oak_stairs[facing=east,...]`.
 */
@Unstable
public data class BlockRecord(
    override val at: Instant,
    override val actor: Actor?,
    override val cause: CauseKind,
    override val rolledBack: Instant?,
    override val place: BlockPlace,
    override val cursor: HistoryCursor,
    public val action: ActionKind,
    public val before: String,
    public val after: String,
) : HistoryRecord

/** An entity appearing, going away or changing in place. [place] is the block it was in. */
@Unstable
public data class EntityRecord(
    override val at: Instant,
    override val actor: Actor?,
    override val cause: CauseKind,
    override val rolledBack: Instant?,
    override val place: BlockPlace,
    override val cursor: HistoryCursor,
    public val action: ActionKind,
    public val entity: UUID,
    /** The entity type key, `minecraft:armor_stand`. */
    public val type: String,
    public val existedBefore: Boolean,
    public val existsAfter: Boolean,
) : HistoryRecord

/**
 * Items moving from one [ItemHolder] to another. One transaction that moved several stacks reads as several flows
 * with the same [cursor].
 */
@Unstable
public data class ItemFlow(
    override val at: Instant,
    override val actor: Actor?,
    override val cause: CauseKind,
    override val rolledBack: Instant?,
    override val place: BlockPlace?,
    override val cursor: HistoryCursor,
    public val item: ItemKind,
    public val amount: Long,
    public val from: ItemHolder,
    public val to: ItemHolder,
) : HistoryRecord

/**
 * Something said, typed or done that moved nothing: a chat line, a join, a death. Never rolled back, so
 * [rolledBack] will be always `null`.
 */
@Unstable
public data class EventRecord(
    override val at: Instant,
    override val actor: Actor?,
    override val cause: CauseKind,
    override val rolledBack: Instant?,
    override val place: BlockPlace?,
    override val cursor: HistoryCursor,
    public val kind: EventKind,
    public val text: String?,
    public val entity: UUID?,
) : HistoryRecord
