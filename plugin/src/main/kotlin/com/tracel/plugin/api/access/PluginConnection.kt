package com.tracel.plugin.api.access

import com.tracel.api.access.AccessError
import com.tracel.api.access.Grant
import com.tracel.api.access.TracelConnection
import com.tracel.api.history.HistoryReads
import com.tracel.api.result.Res
import com.tracel.api.rollback.Rollbacks
import com.tracel.api.write.Attribution
import com.tracel.api.write.QuietWrites
import com.tracel.plugin.api.rollback.Borrows
import com.tracel.plugin.api.rollback.TracelRollbacks
import com.tracel.plugin.api.write.TracelAttribution
import com.tracel.plugin.api.write.TracelQuiet
import com.tracel.plugin.services.TracelServices
import org.bukkit.plugin.Plugin

/**
 * One plugin's [TracelConnection].
 *
 * Grants are read once, from the config `Tracel` started with.
 *
 * Every capability asks [isOpen] on each call, so a disabled plugin is
 * cut off without anyone telling it.
 */
internal class PluginConnection(
    private val owner: Plugin,
    services: TracelServices,
    borrows: Borrows,
    override val reads: HistoryReads,
    private val running: () -> Boolean,
    private val closed: (PluginConnection) -> Unit,
) : TracelConnection {
    @Volatile
    private var ended = false

    override val plugin: String = owner.name
    override val grants: Set<Grant> = services.api.grantsOf(owner.name)
    override val isOpen: Boolean get() = !ended && owner.isEnabled && running()

    private val alive = { isOpen }
    private val rollbacking by lazy { TracelRollbacks(services, plugin, alive, borrows) }
    private val attributing by lazy { TracelAttribution(services, plugin, alive) }
    private val quieting by lazy { TracelQuiet(services, alive) }

    override val rollbacks: Res<Rollbacks, AccessError> get() = granted(Grant.ROLLBACK) { rollbacking }
    override val attribution: Res<Attribution, AccessError> get() = granted(Grant.ATTRIBUTE) { attributing }
    override val quiet: Res<QuietWrites, AccessError> get() = granted(Grant.QUIET) { quieting }

    override fun close() {
        if (ended) return
        ended = true
        closed(this)
    }

    private inline fun <T> granted(grant: Grant, capability: () -> T): Res<T, AccessError> = when {
        !isOpen -> Res.Err(AccessError.Revoked)
        grant !in grants -> Res.Err(AccessError.NotGranted(grant))
        else -> Res.Ok(capability())
    }
}
