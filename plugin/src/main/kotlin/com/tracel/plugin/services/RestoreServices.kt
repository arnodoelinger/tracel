package com.tracel.plugin.services

import com.tracel.engine.rollback.plan.WorldQuery
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.bukkit.block.warmFluidShapes
import com.tracel.plugin.bukkit.world.playerIsOnline
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.rollback.SignaledRollbacks
import com.tracel.plugin.rollback.composer.RollbackComposer
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.outside.OutsideRollbacks
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.fluid.FluidFreeze

/** The side of the plugin that puts the world back. */
class RestoreServices(services: TracelServices) {
    val restorer: MaterialRestorer = MaterialRestorer(services)
    val structureRestorer: StructureRestorer = StructureRestorer(services)
    val composite: RollbackGenius =
        SignaledRollbacks(
            OutsideRollbacks(services, RollbackComposer(services, structureRestorer, restorer, restorer), Extensions.live),
            Extensions.live,
        )
    internal val fluidFreeze: FluidFreeze = FluidFreeze()
    val worldQuery: WorldQuery = WorldQuery(::playerIsOnline)

    /** Tables the rollback path would otherwise build on a region thread the first time it needs them. */
    fun warmRollback() = warmFluidShapes()
}
