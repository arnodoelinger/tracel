package com.tracel.engine.rollback.structure

import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.EntityShape
import java.util.UUID

/** One stuff a rollback has to do to the world's shape. */
public sealed interface StructureStep {
    public val at: BlockPos

    /**
     * Put [target] back at [at].
     *
     * [expected] is what the log says stands there now. Checked before anything is written, so a
     * coordinate somebody has since rebuilt gets reported instead of silently flattened.
     */
    public data class SetBlock(
        override val at: BlockPos,
        public val target: BlockShape,
        public val expected: BlockShape,
    ) : StructureStep

    /**
     * Make [entity] exist again in [shape].
     *
     * Keyed by the original UUID, so applying this twice leaves one boat rather than two, and an
     * undo afterwards knows which one to take away again.
     */
    public data class SpawnEntity(
        override val at: BlockPos,
        public val entity: UUID,
        public val shape: EntityShape,
    ) : StructureStep

    /**
     * Take [entity] away — it was spawned inside the window being rolled back.
     *
     * Carries the [shape] it had when it was taken, which is what makes the step reversible: an
     * undo has to put back the same boat, in the same place, facing the same way, and a step that
     * only knew a UUID could not.
     */
    public data class RemoveEntity(
        override val at: BlockPos,
        public val entity: UUID,
        public val shape: EntityShape,
    ) : StructureStep
}

/**
 * The step that puts back what this one changed.
 *
 * Every structural step is reversible.
 */
public fun StructureStep.inverse(): StructureStep = when (this) {
    is StructureStep.SetBlock -> StructureStep.SetBlock(at, expected, target)
    is StructureStep.SpawnEntity -> StructureStep.RemoveEntity(at, entity, shape)
    is StructureStep.RemoveEntity -> StructureStep.SpawnEntity(at, entity, shape)
}
