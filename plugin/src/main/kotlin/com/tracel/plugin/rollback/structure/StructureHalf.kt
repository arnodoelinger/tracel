package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.StructureReport

/**
 * World-log half as composition sees it.
 *
 * Blocks and hulls only; cargo is the other half's problem.
 */
interface StructureHalf {
    /** Worlds named by [steps] must be loaded. Checked before the ledger moves anything. */
    fun preflight(steps: List<StructureStep>): PreflightResult

    /** Apply [steps]. [StructureReport.applied] is what stands now, not what was planned — undo relies on it. */
    suspend fun restore(
        steps: List<StructureStep>,
        pass: StructurePass = StructurePass(),
    ): StructureReport

    /**
     * Fluids, once every pass of a job has [written]: [drain] streams that lost their source (forward
     * only: an undo is putting those very streams back), then one tick for what was put back.
     */
    suspend fun settleFluids(written: List<StructureStep>, drain: Boolean): StructureReport

    /** Physics was off. Run after structure (!) and cargo. */
    suspend fun wakeRedstone(positions: Sequence<BlockPos>)
}
