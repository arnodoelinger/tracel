package com.tracel.model.holder

import com.tracel.model.world.BlockPos

/**
 * A total order on holders that depends on nothing but their identity.
 *
 * Balancer pairs gains against losses in this order, so it must never follow `toString`.
 */
public object HolderOrder : Comparator<HolderId> {
    override fun compare(a: HolderId, b: HolderId): Int {
        val byKind = a.rank().compareTo(b.rank())
        if (byKind != 0) return byKind
        return when (a) {
            is HolderId.Block -> comparePos(a.pos, (b as HolderId.Block).pos)
            is HolderId.PlacedBlock -> comparePos(a.pos, (b as HolderId.PlacedBlock).pos)
            is HolderId.Player -> a.uuid.compareTo((b as HolderId.Player).uuid)
            is HolderId.PlayerStash -> a.uuid.compareTo((b as HolderId.PlayerStash).uuid)
            is HolderId.Entity -> a.uuid.compareTo((b as HolderId.Entity).uuid)
            is HolderId.PlacedEntity -> a.uuid.compareTo((b as HolderId.PlacedEntity).uuid)
            is HolderId.ItemEntity -> a.uuid.compareTo((b as HolderId.ItemEntity).uuid)
            is HolderId.Escrow -> a.job.raw.compareTo((b as HolderId.Escrow).job.raw)
            is HolderId.Source -> a.kind.compareTo((b as HolderId.Source).kind)
            is HolderId.Sink -> a.kind.compareTo((b as HolderId.Sink).kind)
        }
    }

    private fun comparePos(a: BlockPos, b: BlockPos): Int =
        compareValuesBy(a, b, { it.world.uuid }, { it.x }, { it.y }, { it.z })

    private fun HolderId.rank(): Int = when (this) {
        is HolderId.Block -> 0
        is HolderId.PlacedBlock -> 1
        is HolderId.Player -> 2
        is HolderId.PlayerStash -> 3
        is HolderId.Entity -> 4
        is HolderId.PlacedEntity -> 5
        is HolderId.ItemEntity -> 6
        is HolderId.Escrow -> 7
        is HolderId.Source -> 8
        is HolderId.Sink -> 9
    }
}
