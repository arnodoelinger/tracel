package com.tracel.api.rollback

import com.tracel.api.Unstable
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName

/**
 * A planned rollback, not yet applied. It holds a fingerprint of what history said when it was planned; should
 * the world move on before [apply], the answer is [RollbackError.Stale] with a fresh plan.
 *
 * Used once: a second [apply] answers [RollbackError.Moved].
 */
@Unstable
public interface RollbackReady {
    /** What it was planned from. */
    public val draft: RollbackDraft

    /** Block and entity steps it takes. */
    public val blocks: Int

    /** Of [blocks], the steps that put something back. */
    public val restores: Int

    /** Of [blocks], the steps that take something away. */
    public val removals: Int

    /** Entities it brings back. */
    public val entities: Int

    /** The entities it brings back, counted by type. */
    public val entityTypes: Map<NamespacedName, Int>

    /** The blocks it sets, for showing them before they are. */
    public val blockChanges: List<PlannedBlock>

    /** What happens to items. */
    public val balance: ItemBalance

    /**
     * `false` when some history was still on its way to disk when it was planned,
     * so the newest may be missing.
     */
    public val complete: Boolean

    /** Records it takes back that came from a `CoreProtect` import. */
    public val imported: Int

    /** Addon records it takes back outside the world, through their `Reversible`. */
    public val outside: Int

    /** True when there is nothing to do. */
    public val isEmpty: Boolean

    /**
     * Gets the world ready for [apply]: parses the blocks it writes and loads the chunks it touches, writing nothing.
     * Worth it for a plan that is looked at for a while before it is applied.
     */
    public suspend fun warm()

    /** Applies it. */
    public suspend fun apply(): Res<RollbackApplied, RollbackError>
}

/** Items a rollback moves: [taken] lots go back, [unmade] crafts come apart, [minted] lots are made anew. */
@Unstable
public data class ItemBalance(public val taken: Int, public val unmade: Int, public val minted: Int)

/** A block a rollback sets: [becomes] is what it will be, [was] what it is expected to be now. Both as block data. */
@Unstable
public data class PlannedBlock(public val place: BlockPlace, public val becomes: String, public val was: String)
