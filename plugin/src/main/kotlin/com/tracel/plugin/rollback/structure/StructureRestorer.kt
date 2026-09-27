package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.fluid.settleWritten
import com.tracel.plugin.rollback.structure.redstone.wakeRedstoneAt
import com.tracel.plugin.rollback.trace.RollbackTrace
import java.util.logging.Logger

/**
 * Structural half of a rollback.
 *
 * Physics off on writes. Fluids get one tick at the end. Standalone first.
 */
class StructureRestorer(internal val services: TracelServices) : StructureHalf {
    internal val logger = Logger.getLogger(StructureRestorer::class.java.name)

    override fun preflight(steps: List<StructureStep>): PreflightResult = preflightWorlds(steps)

    override suspend fun restore(
        steps: List<StructureStep>,
        pass: StructurePass,
        trace: RollbackTrace,
    ): StructureReport = restoreSteps(
        steps, pass.force, trace, pass.phase,
        pass.keepCargoFor, pass.ledgerCargoFor, pass.ledgerHeldBy, pass.dumpHeldCargo, pass.driftOnly,
    )

    override suspend fun settleFluids(written: List<StructureStep>, drain: Boolean): StructureReport =
        settleWritten(written, drain)

    override suspend fun wakeRedstone(positions: Sequence<BlockPos>) = wakeRedstoneAt(positions)
}
