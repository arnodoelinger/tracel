package com.tracel.engine.rollback.structure

import com.tracel.engine.rollback.plan.RollbackPlan

/**
 * Everything one rollback does, in the order it has to be done.
 *
 * The three phases are dependency. A container has to exist before the ledger can put
 * anything back into it, and it must not be taken away until the ledger has finished
 * taking things out of it — so anything being created goes first, the material moves
 * in the middle, and anything being destroyed goes last.
 *
 * Get that order wrong and the failure is not a crash. It is a chest that restores empty
 * because it was placed after its contents were delivered to a coordinate holding air.
 */
public data class CompositeRollbackPlan(
    public val create: List<StructureStep>,
    public val material: RollbackPlan,
    public val destroy: List<StructureStep>,
) {
    public val structureCount: Int get() = create.size + destroy.size

    public val isEmpty: Boolean get() = create.isEmpty() && destroy.isEmpty() && material.steps.isEmpty()
}
