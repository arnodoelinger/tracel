package com.tracel.engine.rollback.structure

import com.tracel.engine.rollback.plan.RollbackPlan

/** Create, then ledger, then destroy — reverse that and the chest comes back empty. */
public data class CompositeRollbackPlan(
    public val create: List<StructureStep>,
    public val material: RollbackPlan,
    public val destroy: List<StructureStep>,
) {
    /** Structure count. */
    public val structureCount: Int get() = create.size + destroy.size

    /** Whether a structure is empty. */
    public val isEmpty: Boolean get() = create.isEmpty() && destroy.isEmpty() && material.steps.isEmpty()
}
