package com.tracel.storage.ports.log

import com.tracel.storage.codec.Txn
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.intern.Interning
import it.unimi.dsi.fastutil.ints.IntSet
import java.lang.foreign.MemorySegment

/**
 * What a [LookupFilter] lets through, asked of a transaction's record.
 *
 * A transaction counts as in a region or a world when it happened there
 * or any holder it touches is a block there.
 */
internal object TransactionFilter {
    /** Whether the transaction [record] is one [filter] asks for. [materialIds] `null` means any item. */
    fun accepts(
        unit: StorageUnit,
        interning: Interning,
        record: MemorySegment,
        filter: LookupFilter,
        ids: FilterIds,
        materialIds: IntSet?,
    ): Boolean = unit.wants(interning, record, filter, ids, materialIds)

    private fun StorageUnit.wants(
        interning: Interning,
        record: MemorySegment,
        filter: LookupFilter,
        ids: FilterIds,
        materialIds: IntSet?,
    ): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val regionWorldId = ids.regionWorld
        val filterWorldId = ids.world
        val epochMillis = Txn.epochMillis[record]
        val since = filter.since
        val until = filter.until
        if (since != null && epochMillis < since) return false
        if (until != null && epochMillis > until) return false
        val cause = Txn.cause[record]
        if (cause.isBookkeeping) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false

        val flowCount = Txn.flowCount[record]
        if (holderIds.isNotEmpty()) {
            var hit = Txn.causedBy[record] in holderIds
            if (!hit) {
                for (i in 0 until flowCount) {
                    if (Txn.source[record, i] in holderIds || Txn.destination[record, i] in holderIds) {
                        hit = true
                        break
                    }
                }
            }
            if (!hit) return false
        }
        if (excludedIds.isNotEmpty()) {
            if (Txn.causedBy[record] in excludedIds) return false
            for (i in 0 until flowCount) {
                if (Txn.source[record, i] in excludedIds) return false
                if (Txn.destination[record, i] in excludedIds) return false
            }
        }
        if (materialIds != null) {
            var hit = false
            for (i in 0 until flowCount) {
                if (Txn.item[record, i] in materialIds) {
                    hit = true
                    break
                }
            }
            if (!hit) return false
        }
        if (filterWorldId != null && !inWorld(interning, record, flowCount, filterWorldId)) return false
        val region = filter.region
        if (region != null) {
            if (regionWorldId == null || !inRegion(interning, record, flowCount, region, regionWorldId)) return false
        }
        return true
    }

    private fun StorageUnit.inRegion(
        interning: Interning,
        record: MemorySegment,
        flowCount: Int,
        region: LookupRegion,
        regionWorldId: Int,
    ): Boolean {
        if (Txn.world[record] == regionWorldId &&
            region.containsBlock(Txn.x[record], Txn.y[record], Txn.z[record])
        ) {
            return true
        }
        if (holderInRegion(interning, Txn.causedBy[record], region, regionWorldId)) return true
        for (i in 0 until flowCount) {
            if (holderInRegion(interning, Txn.source[record, i], region, regionWorldId)) return true
            if (holderInRegion(interning, Txn.destination[record, i], region, regionWorldId)) return true
        }
        return false
    }

    private fun StorageUnit.inWorld(interning: Interning, record: MemorySegment, flowCount: Int, worldId: Int): Boolean {
        if (Txn.world[record] == worldId) return true
        if (holderWorldId(interning, Txn.causedBy[record]) == worldId) return true
        for (i in 0 until flowCount) {
            if (holderWorldId(interning, Txn.source[record, i]) == worldId) return true
            if (holderWorldId(interning, Txn.destination[record, i]) == worldId) return true
        }
        return false
    }

    private fun StorageUnit.holderInRegion(interning: Interning, id: Int, region: LookupRegion, regionWorldId: Int): Boolean {
        val at = holderBlock(interning, id) ?: return false
        return at.worldId == regionWorldId && region.containsBlock(at.x, at.y, at.z)
    }

    private fun StorageUnit.holderWorldId(interning: Interning, id: Int): Int? = holderBlock(interning, id)?.worldId

    private fun StorageUnit.holderBlock(interning: Interning, id: Int): HolderBlock? {
        if (id == 0) return null
        return when (val holder = interning.resolveHolder(this, id)) {
            is HolderId.Block -> HolderBlock(
                interning.findWorldId(this, holder.world) ?: return null,
                holder.x, holder.y, holder.z,
            )

            is HolderId.PlacedBlock -> HolderBlock(
                interning.findWorldId(this, holder.world) ?: return null,
                holder.x, holder.y, holder.z,
            )

            else -> null
        }
    }

    private data class HolderBlock(val worldId: Int, val x: Int, val y: Int, val z: Int)
}
