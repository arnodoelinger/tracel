package com.tracel.plugin.rollback.structure

import com.tracel.engine.log.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.fluid.FluidFreeze

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

    /** Holds still every cell [steps] write, see [FluidFreeze], until [work] returns. */
    suspend fun <T> holdingFluids(steps: List<StructureStep>, work: suspend () -> T): T

    /** Holds still every fluid in [region] until [work] returns: plan and write there against a world that waits. */
    suspend fun <T> holdingArea(region: LookupRegion?, work: suspend () -> T): T

    /** Lets go of the area [holdingArea] holds around the caller, if any, once the last write is in. */
    suspend fun letGoOfArea()

    /**
     * Fluids, once a job has let go of what it [written]: the tick a neighbor update would have given them, where the
     * world may now be other than it stood.
     *
     * Inside [asItStood] every cell is back exactly as it stood, so only flowing water caught mid-stream is ticked
     * there, and water against it beyond the area; without one, every fluid in and against what was written is.
     *
     * What moves from here on is the world's doing, logged as such.
     */
    suspend fun settleFluids(written: List<StructureStep>, asItStood: LookupRegion? = null)

    /** Physics was off. Run after structure (!) and cargo. */
    suspend fun wakeRedstone(positions: Sequence<BlockPos>)
}
