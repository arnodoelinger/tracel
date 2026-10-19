package com.tracel.api.access

import com.tracel.api.Unstable
import com.tracel.api.history.HistoryReads
import com.tracel.api.result.Res
import com.tracel.api.rollback.Rollbacks
import com.tracel.api.write.Attribution
import com.tracel.api.write.QuietWrites

/**
 * One plugin's way in, from `Tracel.connect(plugin)`. It may do what its [grants] allow; each capability is
 * a scope, so a call it has no grant for does not even compile:
 *
 * ```
 * val tracel = Tracel.connect(this).orThrow()
 * with(tracel.rollbacks.orThrow()) {
 *     griefer.rollback { last(1.hours); within(spawn.around(50)) }.preview()
 * }
 * ```
 *
 * Everything done through it is signed with the plugin's name. Closed with the plugin, or by [close].
 */
@Unstable
public interface TracelConnection : AutoCloseable {
    /** The plugin's name, as lookups show it: `Arena (plugin)`. */
    public val plugin: String

    /** What it may do: every grant, unless the admin narrowed it. */
    public val grants: Set<Grant>

    /**
     * `false` once closed or once the plugin is disabled; every capability then answers
     * [AccessError.Revoked].
     */
    public val isOpen: Boolean

    /** Reading history, open to everyone. */
    public val reads: HistoryReads

    /** Rolling back, with [Grant.ROLLBACK]. */
    public val rollbacks: Res<Rollbacks, AccessError>

    /** Writing to the world and to history in someone's name, with [Grant.ATTRIBUTE]. */
    public val attribution: Res<Attribution, AccessError>

    /** Writing to the world past history, with [Grant.QUIET]. */
    public val quiet: Res<QuietWrites, AccessError>

    /**
     * Ends the connection. Handles taken from it answer [AccessError.Revoked]
     * from then on.
     *
     * Safe to call twice.
     */
    override fun close()
}
