package com.tracel.api.rollback

import com.tracel.api.Unstable

/** The number a rollback is known by, for `Rollbacks.undo` and the `/tracel rollback undo` button. */
@Unstable
public data class RollbackJobId(public val raw: Long) : Comparable<RollbackJobId> {
    override fun compareTo(other: RollbackJobId): Int = raw.compareTo(other.raw)

    override fun toString(): String = "#$raw"
}
