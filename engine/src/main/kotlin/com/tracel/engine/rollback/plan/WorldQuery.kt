package com.tracel.engine.rollback.plan

import java.util.*

/** What the planner needs to know about the live server that the ledger itself never tracks. */
public fun interface WorldQuery {
    /** Whether [player] is in the world right now, and so can be handed something directly. */
    public fun isOnline(player: UUID): Boolean
}
