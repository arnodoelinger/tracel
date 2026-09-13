package com.tracel.engine.rollback.structure

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import java.util.UUID

/** One stuff a rollback has to do to the world's shape. */
public sealed interface StructureStep {
    /** The position. */
    public val at: BlockPos

    /**
     * Puts [target] back at [at].
     *
     * [expected] is the state currently recorded at this position. If it changed since the plan
     * was created, the step is rejected instead of overwriting somebody else's changes.
     */
    public data class SetBlock(
        override val at: BlockPos,
        public val target: BlockShape,
        public val expected: BlockShape,
    ) : StructureStep

    /**
     * Spawns [entity] with [shape].
     *
     * [expected] is the entity's current recorded shape, or null if it no longer exists.
     * It is used to build the inverse step and to detect changes made after the plan was created.
     */
    public data class SpawnEntity(
        override val at: BlockPos,
        public val entity: UUID,
        public val shape: EntityShape,
        public val expected: EntityShape? = null,
    ) : StructureStep

    /**
     * Removes [entity], preserving its [shape] so the step can be undone.
     *
     * This is used for entities created inside the rollback window.
     */
    public data class RemoveEntity(
        override val at: BlockPos,
        public val entity: UUID,
        public val shape: EntityShape,
    ) : StructureStep
}
