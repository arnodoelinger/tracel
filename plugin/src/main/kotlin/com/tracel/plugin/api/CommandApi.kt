package com.tracel.plugin.api

import com.tracel.api.actor.Actor
import com.tracel.api.admin.Admin
import com.tracel.api.history.HistoryReads
import com.tracel.api.rollback.Rollbacks
import com.tracel.model.holder.HolderId
import com.tracel.plugin.api.admin.TracelAdmin
import com.tracel.plugin.api.read.HistoryRows
import com.tracel.plugin.api.read.TracelReads
import com.tracel.plugin.api.rollback.TracelRollbacks
import com.tracel.plugin.services.TracelServices

private const val SIGNER = "Tracel"

/**
 * What `/tracel` talks to: the same reads and rollbacks every plugin gets,
 * with no grant to ask for. The commands are the API's first customer, so what
 * they cannot do through it is a hole in it.
 */
internal class CommandApi(private val services: TracelServices) {
    /** The rows under every history read. */
    private val rows: HistoryRows = HistoryRows(services)

    /** Purge, export, import, migration and status. */
    val admin: Admin = TracelAdmin(services, SIGNER)

    /** History reads. */
    val reads: HistoryReads = TracelReads(services, rows)

    /**
     * Rollbacks signed as [by], a player or the console (`null`).
     *
     * With [confirmed] the sender has agreed to bring back more entities than the limit.
     */
    fun rollbacks(by: Actor.Player?, confirmed: Boolean = false): Rollbacks = TracelRollbacks(
        services, SIGNER, { true }, services.borrows, by = by?.let { HolderId.Player(it.uuid) }, confirmed = confirmed,
    )
}
