package com.tracel.engine.rollback.structure

import com.tracel.engine.rollback.plan.RollbackPlan

/**
 * A whole rollback in the only order that works: [create] what has to appear, then the [material] moves, then [destroy]
 * what has to go. Reverse that and, for one, a container comes back empty.
 */
public data class CompositeRollbackPlan(
    public val create: List<StructureStep>,
    public val material: RollbackPlan,
    public val destroy: List<StructureStep>,
) {
    /** How many world steps there are, [create] and [destroy] together. */
    public val structureCount: Int get() = create.size + destroy.size

    /** Whether there is nothing to do at all: no world steps and no material steps. */
    public val isEmpty: Boolean get() = create.isEmpty() && destroy.isEmpty() && material.steps.isEmpty()
}
