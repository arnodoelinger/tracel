package com.tracel.plugin

import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.i18n.Messages
import com.tracel.plugin.startup.TracelRuntime
import com.tracel.plugin.startup.enableTracel
import com.tracel.plugin.status.CriticalDiskSpace
import com.tracel.storage.format.StoreFormatException
import com.tracel.storage.ports.ops.ImportInterrupted
import com.tracel.plugin.util.killServer
import com.tracel.plugin.util.serverIsStopping
import com.tracel.plugin.util.stopping
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private var runtime: TracelRuntime? = null

    override fun onEnable() {
        try {
            runtime = enableTracel(this)
        } catch (failure: Throwable) {
            if (failure is CriticalDiskSpace || failure is StoreFormatException || failure is ImportInterrupted) logger.severe(failure.message)
            else logger.log(Level.SEVERE, "Tracel failed to enable. The server cannot run without a ledger.", failure)
            killServer(this)
            throw failure
        }
    }

    override fun onDisable() {
        Messages.unload()
        val run = runtime
        stopping(logger, "storage") {
            val live = run ?: return@stopping
            val clean = live.storage.closeAfter(5_000L) {
                stopping(logger, "the WorldEdit hook") { live.services.worldEdit?.close() }
                stopping(logger, "the last captures") { live.lastCaptures() }
                live.drain.cancel()
                live.entityDrain.cancel()
                live.formDrain.cancel()
                live.releaseDrain.cancel()
                live.services.scope.coroutineContext[Job]?.let {
                    it.cancel()
                    it.join()
                }
            }
            if (!clean) logger.warning("Tracel stopped waiting for its last writes after 5 seconds; the rest still goes")
        }
        logger.info("Tracel disabled.")
        if (!serverIsStopping()) {
            logger.severe("Tracel was disabled while the server is still running. The server cannot run without a ledger.")
            killServer(this)
        }
    }

    /** Capture failure. */
    internal fun captureFailures() = CoroutineExceptionHandler { _, failure ->
        logger.log(Level.WARNING, "Tracel background task failed: ${failure.message}", failure)
    }

    /** If capture dies, kill the server. */
    internal fun haltIfCaptureDies(job: Job, what: String) {
        job.invokeOnCompletion { failure ->
            if (!isEnabled || serverIsStopping()) return@invokeOnCompletion
            if (failure == null) return@invokeOnCompletion
            logger.log(Level.SEVERE, "Tracel\'s $what died. The server cannot run without a ledger.", failure)
            killServer(this)
        }
    }

    /** Item form writer. */
    internal suspend fun writeItemForms(services: TracelServices) {
        val batch = PendingItemForms.drain()
        if (batch.isEmpty()) return
        try {
            services.itemForms.rememberAll(batch)
        } catch (failure: Throwable) {
            PendingItemForms.requeue(batch)
            throw failure
        }
    }
}
