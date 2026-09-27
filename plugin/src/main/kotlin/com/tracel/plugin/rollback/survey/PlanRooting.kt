package com.tracel.plugin.rollback.survey

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.LotId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.blockPos
import java.util.*

/**
 * Records where a lot's item traces back to before the window opened — the "home" that undo
 * will eventually put it back into.
 */
internal fun MutableMap<LotId, HolderId>.rootedAt(
    lot: LotId,
    source: HolderId,
    keepOn: Set<UUID> = emptySet(),
) {
    val current = this[lot]
    if (source is HolderId.Player && current is HolderId.Entity && current.uuid in keepOn) return
    if (source !is HolderId.Source) {
        put(lot, source)
        return
    }
    if (containsKey(lot)) return
    put(lot, source)
}

/**
 * Catches an item that was minted into a mob and immediately moved out of it, in the same
 * transaction.
 */
internal fun Transaction.mintedStraightThrough(): Map<HolderId, HolderId> {
    var mintedInto: MutableMap<Pair<HolderId, ItemKey>, HolderId>? = null
    for ((itemKey, _, source, into, kind) in flows) {
        if (kind != FlowKind.MINT) continue
        if (into !is HolderId.Entity) continue
        if (source !is HolderId.Source) continue
        (mintedInto ?: HashMap<Pair<HolderId, ItemKey>, HolderId>().also { mintedInto = it })[into to itemKey] =
            source
    }
    val minted = mintedInto ?: return emptyMap()
    val out = HashMap<HolderId, HolderId>(minted.size)
    for (flow in flows) {
        if (flow.kind != FlowKind.MOVE) continue
        val from = minted[flow.source to flow.itemKey] ?: continue
        out[flow.source] = from
    }
    return out
}

/**
 * A root that was a chest, then got blown up, then had something else placed in the same spot
 * has nowhere sane to go back to — restoring "into" that cell would either resurrect the wrong
 * block or land in whatever replaced it.
 *
 * For any root sitting on a position in [vanished], swap it to [SourceKind.UNATTRIBUTED] so the
 * item is accounted for without pretending it has a home.
 */
internal fun RollbackTarget.awayFromAirToAir(vanished: Set<BlockPos>): RollbackTarget {
    if (vanished.isEmpty()) return this
    return when (this) {
        is RollbackTarget.Uniform -> this
        is RollbackTarget.PerRoot -> {
            var changed = false
            val next = HashMap<LotId, HolderId>(byRoot.size)
            for ((lot, dest) in byRoot) {
                val pos = dest.blockPos()
                if (pos != null && pos in vanished) {
                    next[lot] = HolderId.Source(SourceKind.UNATTRIBUTED)
                    changed = true
                } else {
                    next[lot] = dest
                }
            }
            if (changed) RollbackTarget.PerRoot(next) else this
        }
    }
}

/**
 * For every lot still sitting in a "Sink" (something the item was consumed into, e.g. fire), walk
 * up its split ancestry via [parentOf] until an ancestor rooted at a "Source" turns up, and inherit
 * that "Source" instead of the chest.
 *
 * That way the burned half vanishes on undo like it should, while a genuinely real lot (never touched
 * by a source) keeps its real root untouched.
 */
internal fun MutableMap<LotId, HolderId>.inheritMintedBurns(
    sittingAt: Map<LotId, HolderId>,
    parentOf: Map<LotId, LotId>,
) {
    for (lot in keys.toList()) {
        if (sittingAt[lot] !is HolderId.Sink) continue
        var id = lot
        val seen = HashSet<LotId>()
        while (seen.add(id)) {
            val origin = this[id]
            if (origin is HolderId.Source) {
                this[lot] = origin
                break
            }
            id = parentOf[id] ?: break
        }
    }
}

/**
 * Adjusts the current rollback target by reassigning lots that belong to entities
 * or placed entities that are part of the specified set of gone UUIDs.
 */
internal fun RollbackTarget.awayFromEntitiesGone(gone: Set<UUID>): RollbackTarget {
    if (gone.isEmpty() || this !is RollbackTarget.PerRoot) return this
    var changed = false
    val next = HashMap<LotId, HolderId>(byRoot.size)
    for ((lot, dest) in byRoot) {
        val uuid = (dest as? HolderId.Entity)?.uuid ?: (dest as? HolderId.PlacedEntity)?.uuid
        if (uuid != null && uuid in gone) {
            next[lot] = HolderId.Source(SourceKind.UNATTRIBUTED)
            changed = true
        } else {
            next[lot] = dest
        }
    }
    return if (changed) RollbackTarget.PerRoot(next) else this
}
