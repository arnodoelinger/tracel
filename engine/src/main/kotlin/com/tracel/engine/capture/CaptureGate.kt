package com.tracel.engine.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldId

/**
 * What a listener actually calls to record something. It never blocks and never throws: every method returns `false`
 * when the event was dropped, which a caller that wants to know can look at and one that does not may ignore, because
 * the loss is counted either way.
 */
public interface CaptureGate {
    /** A quantity of one item key moving between two holders. The whole hot path, in one call. */
    public fun move(
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        itemKey: ItemKey,
        from: HolderId,
        to: HolderId,
        quantity: Long,
    ): Boolean

    /** One unbalanced delta: a mint or a burn the balancer will pair against a source or a sink. */
    public fun single(
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        itemKey: ItemKey,
        holder: HolderId,
        delta: Long,
    ): Boolean

    /** A diff's worth of deltas. The list is the caller's; nothing here keeps a reference to it. */
    public fun many(cause: CauseKind, causedBy: HolderId?, epochMillis: Long, deltas: List<InventoryDelta>): Boolean

    /** Block edits that carry no extras. Returns `false` for anything else, so the caller goes [parkedBlocks]. */
    public fun blocks(
        cause: CauseKind,
        action: ActionKind,
        causedBy: HolderId?,
        epochMillis: Long,
        world: WorldId,
        edits: List<BlockEdit>,
    ): Boolean

    /** Block edits the fast path cannot carry. */
    public fun parkedBlocks(edits: BlockEdits): Boolean

    /** Item deltas that carry a place. */
    public fun parkedDeltas(placed: PlacedDeltas): Boolean

    /** Everything [from] held went to [to]. */
    public fun release(cause: CauseKind, causedBy: HolderId?, epochMillis: Long, from: HolderId, to: HolderId): Boolean
}
