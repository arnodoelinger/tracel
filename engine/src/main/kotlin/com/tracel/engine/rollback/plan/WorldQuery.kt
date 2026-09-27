package com.tracel.engine.rollback.plan

import java.util.*

/** What the planner needs to know about the live server that the ledger itself never tracks. */
public fun interface WorldQuery {
    public fun isOnline(player: UUID): Boolean
}
