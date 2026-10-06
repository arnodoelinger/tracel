package com.tracel.engine.rollback.plan

/**
 * A plan the caller has already worked out, with the ledger version [witness] it read just before working it out.
 *
 * The two travel together because the witness is what makes handing in a plan as safe as computing one: without it the
 * staleness check could not tell whether the ledger moved, and would have to skip it rather than pass it.
 */
public data class PreparedPlan(public val plan: RollbackPlan, public val witness: Long)
