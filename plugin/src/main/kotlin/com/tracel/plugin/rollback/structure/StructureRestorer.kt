package com.tracel.plugin.rollback.structure

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.fluid.settleWritten
import com.tracel.plugin.rollback.structure.fluid.wakeCells
import com.tracel.plugin.rollback.structure.redstone.wakeRedstoneAt
import java.util.logging.Logger

/**
 * Structural half of a rollback.
 *
 * Physics off on writes, fluids held still until the job is done, then woken. Standalone first.
 */
class StructureRestorer(internal val services: TracelServices) : StructureHalf {
    internal val logger = Logger.getLogger(StructureRestorer::class.java.name)

    override fun preflight(steps: List<StructureStep>): PreflightResult = preflightWorlds(steps)

    override suspend fun restore(
        steps: List<StructureStep>,
        pass: StructurePass,
    ): StructureReport = restoreSteps(
        steps, pass.force,
        pass.keepCargoFor, pass.ledgerCargoFor, pass.ledgerHeldBy, pass.dumpHeldCargo, pass.driftOnly,
    )

    override suspend fun <T> holdingFluids(steps: List<StructureStep>, work: suspend () -> T): T =
        services.fluidFreeze.holding(steps, { wakeCells(it) }, work)

    override suspend fun <T> holdingArea(region: LookupRegion?, work: suspend () -> T): T =
        services.fluidFreeze.holdingArea(region, { wakeCells(it) }, work)

    override suspend fun letGoOfArea() = services.fluidFreeze.letGoOfArea()

    override suspend fun settleFluids(written: List<StructureStep>, asItStood: LookupRegion?) =
        settleWritten(written, asItStood)

    override suspend fun wakeRedstone(positions: Sequence<BlockPos>) = wakeRedstoneAt(positions)
}
