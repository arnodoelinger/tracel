package com.tracel.plugin.services.specifics

import com.tracel.engine.world.GroundPositions
import com.tracel.plugin.listener.session.specifics.InspectorState
import com.tracel.plugin.listener.support.drop.BlockDrop
import com.tracel.plugin.listener.support.drop.HullDrop
import com.tracel.plugin.listener.support.guard.FreezeGuard
import com.tracel.plugin.listener.support.guard.SelfManagedWorldGuard
import com.tracel.plugin.listener.support.guard.SpawnGuard
import com.tracel.plugin.listener.support.redstone.RedstoneTrigger
import com.tracel.plugin.util.whereabouts.EntityWhereabouts
import com.tracel.plugin.util.whereabouts.GroundWhereabouts

private const val WHEREABOUTS_KEPT = 200_000

/** What listeners keep between events. Nothing here needs the rest of the services to be built. */
class ListenerState(groundPositions: GroundPositions) {
    val redstoneTriggers: RedstoneTrigger = RedstoneTrigger()
    val whereabouts: EntityWhereabouts = EntityWhereabouts(capacity = WHEREABOUTS_KEPT)
    val groundWhereabouts: GroundWhereabouts = GroundWhereabouts(groundPositions)
    val selfManagedSpawns: SpawnGuard = SpawnGuard()
    val blockDrops: BlockDrop = BlockDrop()
    val hullDrops: HullDrop = HullDrop()
    val inspectors: InspectorState = InspectorState()
    val frozen: FreezeGuard = FreezeGuard()
    val selfManagedWorld: SelfManagedWorldGuard = SelfManagedWorldGuard()
}
