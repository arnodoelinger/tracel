package com.tracel.plugin.api.addon

import com.tracel.api.access.AccessError
import com.tracel.api.access.Grant
import com.tracel.api.access.TracelConnection
import com.tracel.api.admin.Admin
import com.tracel.api.history.HistoryReads
import com.tracel.api.result.Res
import com.tracel.api.rollback.Rollbacks
import com.tracel.api.write.Attribution
import com.tracel.api.write.QuietWrites

/**
 * A connection with [grants]: for an addon host with no running `Tracel` around it, as in
 * tests.
 *
 * Every capability that would need the server answers [AccessError.Revoked].
 */
internal class DetachedConnection(
    override val plugin: String,
    override val grants: Set<Grant> = Grant.entries.toSet(),
) : TracelConnection {
    @Volatile
    private var ended = false

    override val isOpen: Boolean get() = !ended
    override val reads: HistoryReads get() = error("A detached connection reads nothing.")
    override val rollbacks: Res<Rollbacks, AccessError> get() = Res.Err(AccessError.Revoked)
    override val admin: Res<Admin, AccessError> get() = Res.Err(AccessError.Revoked)
    override val attribution: Res<Attribution, AccessError> get() = Res.Err(AccessError.Revoked)
    override val quiet: Res<QuietWrites, AccessError> get() = Res.Err(AccessError.Revoked)

    override fun close() {
        ended = true
    }
}
