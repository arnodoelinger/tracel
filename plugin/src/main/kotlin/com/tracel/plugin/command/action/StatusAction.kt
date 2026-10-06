package com.tracel.plugin.command.action

import com.tracel.plugin.command.presenter.StatusPresenter
import com.tracel.plugin.command.presenter.StatusSnapshot
import com.tracel.plugin.i18n.failed
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.unexpected
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.status.health.LAG_PROBE_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/** `/tracel status`: how the plugin is doing, on one screen. */
class StatusAction(private val services: TracelServices) {
    /** Reads the status, then says it. A late capture queue is waited out for a few seconds so it can be measured. */
    fun execute(sender: CommandSender) {
        services.scope.launch {
            try {
                sender.say(StatusPresenter.render(read()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.failed("status.failed", Component.text(unexpected(failure)), tr("common.hint.unknown"))
            }
        }
    }

    private suspend fun read(): StatusSnapshot {
        val stored = withContext(Dispatchers.IO) { services.store.report() }
        val database = services.plugin.dataFolder.resolve("database")

        // What waits right now, before the wait below changes it: captures not written, and ring slots not applied
        val queued = services.pendingCaptures.owedNow() + services.store.capture.backlog.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        // How late the queue is is how long it takes to empty: asked of it now, answered when it is
        val started = System.nanoTime()
        val applied = withTimeoutOrNull(LAG_PROBE_MILLIS) { services.store.capture.awaitApplied(LAG_PROBE_MILLIS) } == true
        val left = (LAG_PROBE_MILLIS - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
        val settled = withTimeoutOrNull(left) { services.pendingCaptures.await(left) } == true
        val took = (System.nanoTime() - started) / 1_000_000
        val beyond = !applied || !settled
        val lag = if (beyond) LAG_PROBE_MILLIS else took

        return StatusSnapshot(
            nowMillis = System.currentTimeMillis(),
            blockRows = stored.blockRows,
            itemRows = stored.itemRows,
            eventRows = stored.eventRows,
            oldestMillis = stored.oldestMillis,
            databaseBytes = stored.liveBytes,
            diskFree = database.usableSpace,
            diskTotal = database.totalSpace,
            queued = queued,
            writesPerSecond = services.writeRate.perSecond(),
            rollbacks = services.composite.activeRollbacks,
            purge = services.purgeSettings,
            lastPurgeMillis = services.lastPurgeMillis,
            lagMillis = lag,
            lagBeyondProbe = beyond,
            mspt = runCatching { Bukkit.getAverageTickTime() }.getOrNull(),
            forwardCompatible = services.forwardCompatible,
            format = services.store.formatVersion,
        )
    }
}
