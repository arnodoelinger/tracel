package com.tracel.api.rollback

import com.tracel.api.Unstable
import com.tracel.api.history.HistoryQuery
import com.tracel.api.result.Res
import com.tracel.api.world.NamespacedName
import com.tracel.api.world.LookupArea
import org.bukkit.entity.Player

/**
 * Rolling back, for a plugin granted `rollback`. Nothing changes until a plan has been looked at and applied:
 * ```
 * with(rollbacks) {
 *     val ready = griefer.rollback { last(2.hours); within(spawn.around(64)) }.preview().orThrow()
 *     if (ready.balance.minted == 0) ready.apply()
 * }
 * ```
 *
 * A rollback needs a time and an area, or it would undo the history of the server.
 */
@Unstable
public interface Rollbacks {
    /** A draft undoing what this player did, narrowed by [build]. */
    public fun Player.rollback(build: HistoryQuery.Builder.() -> Unit): RollbackDraft

    /** A draft undoing what happened in this area, narrowed by [build]. */
    public fun LookupArea.rollback(build: HistoryQuery.Builder.() -> Unit): RollbackDraft

    /** A draft undoing what [query] finds. */
    public fun rollback(query: HistoryQuery): RollbackDraft

    /**
     * Holds [area] for this plugin alone while [block] runs.
     *
     * Another plugin's rollback that reaches into it gets [RollbackError.Borrowed];
     * so does a borrow that overlaps one already held.
     *
     * The [BorrowedArea] lives as long as the block: kept and used afterward, everything made from it answers
     * [RollbackError.Expired].
     */
    public suspend fun <T> borrow(area: LookupArea, block: suspend BorrowedArea.() -> T): Res<T, RollbackError>

    /**
     * Runs [block] holding the one rollback slot, and the fluids of [area] still, from the first plan to the last
     * apply: the previews and applies made inside take no slot of their own, so what a plan saw is what the world still
     * holds when it is written. `null` for [area] holds nothing still.
     *
     * [RollbackError.Busy] when another rollback or undo holds the slot.
     */
    public suspend fun <T> hold(area: LookupArea?, block: suspend () -> T): Res<T, RollbackError>

    /** Undoes rollback [job], one this plugin made earlier, even before a restart. */
    public suspend fun undo(job: RollbackJobId): Res<RollbackUndone, RollbackError>

    /** What undoing [job] would bring back, for asking first. */
    public suspend fun undoPreview(job: RollbackJobId): Res<UndoPreview, RollbackError>

    /** The newest rollback of this plugin that is not undone yet, or `null`. */
    public suspend fun latest(): RollbackJobId?
}

/** An area held by one plugin for the length of a `borrow` block. Its drafts must stay inside it. */
@Unstable
public interface BorrowedArea : Rollbacks {
    /** What is held. */
    public val area: LookupArea

    /** True once the `borrow` block has ended. */
    public val isExpired: Boolean
}

/** What an undo would do beyond blocks: [entityTypes] are the entities it would bring back, by type. */
@Unstable
public data class UndoPreview(public val job: RollbackJobId, public val entityTypes: Map<NamespacedName, Int>) {
    /** How many entities it brings back. */
    public val entities: Int get() = entityTypes.values.sum()
}
