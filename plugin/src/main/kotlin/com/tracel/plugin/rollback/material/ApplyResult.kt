package com.tracel.plugin.rollback.material

/** Per-holder apply. */
internal sealed interface ApplyResult {
    /** Result has been applied. */
    data object Ok : ApplyResult

    /** Result failed. */
    data class Failed(val reason: String) : ApplyResult

    /** Queued result. */
    data class Queued(val note: String) : ApplyResult
}

internal val ENTITY_GONE_AT_PLAN =
    ApplyResult.Failed("the entity was already gone when this rollback was planned, so nothing was taken from it")

internal val ENTITY_GONE_AFTER_RESTORE =
    ApplyResult.Failed("the entity was still not there after the restore should have put it back")
