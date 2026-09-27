package com.tracel.plugin.rollback.result.outcome

/**
 * Reachability before the ledger mutates.
 *
 * Not [Ok] means [Unreachable].
 */
sealed interface PreflightResult {
    data object Ok : PreflightResult
}
