package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.item.ItemKind
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import kotlinx.coroutines.flow.Flow
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Reading history. Open to every plugin; the calls only exist inside the scope:
 *
 * ```
 * with(Tracel.reads) {
 *     block.history { last(1.hours) }.records.collect { ... }
 *     val who = block.lastTouchedBy()
 * }
 * ```
 *
 * Every call copies what it needs off the live object on the calling thread and touches the world no further.
 * What comes back is plain values, safe on any thread.
 */
@Unstable
public interface HistoryReads {
    /** What [query] reads, newest first. */
    public fun history(query: HistoryQuery): History

    /** A fresh, empty [ActorFacts], to learn from the records read through here. */
    public fun actorFacts(): ActorFacts

    /** What happened to this block, newest first. */
    public fun Block.history(build: HistoryQuery.Builder.() -> Unit = {}): History

    /**
     * Whoever last changed this block, the world's own doings left out;
     *
     * [HistoryReadError.NotFound] when nobody has.
     */
    public suspend fun Block.lastTouchedBy(): Res<Actor, HistoryReadError>

    /** What this player did, anywhere, newest first. */
    public fun Player.history(build: HistoryQuery.Builder.() -> Unit = {}): History

    /** The box of blocks within [radius] of this place. */
    public fun BlockPlace.around(radius: Int): LookupArea = LookupArea.Around(this, radius)

    /** What happened inside this area, newest first. */
    public fun LookupArea.history(build: HistoryQuery.Builder.() -> Unit = {}): History

    /** What kind of item this stack is, as history names it. */
    public fun ItemStack.kind(): ItemKind

    /**
     * Where this kind of item last came into being: crafted, dropped by a mob, spawned in creative. For a renamed or
     * enchanted item that is usually the one and only time; for plain cobblestone it is just the latest.
     *
     * Reads back through history until it finds one, so a kind that never appeared reads all of it.
     */
    public suspend fun ItemStack.origin(): Res<ItemFlow, HistoryReadError>

    /** Every move of exactly this kind of item, newest first. */
    public fun ItemStack.trail(build: HistoryQuery.Builder.() -> Unit = {}): History

    /**
     * Who took [kind] out of this container, most taken first,
     * with the query narrowing what is counted.
     */
    public suspend fun Block.whoTook(
        kind: ItemKind,
        build: HistoryQuery.Builder.() -> Unit = {},
    ): Res<List<ContainerTaker>, HistoryReadError>

    /**
     * Waits until what was captured so far is on disk, so a read after it sees it.
     *
     * @return `false` when some of it was still on its way when the wait ran out
     */
    public suspend fun settle(): Boolean

    /**
     * Records as they are written from now on, oldest first, for as long as the flow is collected.
     * Each arrives a second or two after it happened, once it is on disk.
     */
    public fun watch(build: HistoryQuery.Builder.() -> Unit = {}): Flow<HistoryRecord>
}
