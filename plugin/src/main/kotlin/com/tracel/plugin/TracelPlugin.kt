package com.tracel.plugin

import org.bukkit.Bukkit
import com.tracel.plugin.adapter.item.PendingItemForms
import com.tracel.plugin.startup.TracelRuntime
import com.tracel.plugin.startup.enableTracel
import com.tracel.plugin.util.killServer
import com.tracel.plugin.util.serverIsStopping
import com.tracel.plugin.util.stopping
import java.util.logging.Level
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point of `Tracel`.
 */
class TracelPlugin : JavaPlugin() {
    private var runtime: TracelRuntime? = null

    private val SHUTDOWN_WAIT_MILLIS = 5_000L

    override fun onEnable() {
        try {
            runtime = enableTracel(this)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Tracel failed to enable. The server cannot run without a ledger.", failure)
            killServer(this)
            throw failure
        }
    }

    override fun onDisable() {
        val run = runtime
        stopping(logger, "storage") {
            val live = run ?: return@stopping
            val clean = live.storage.closeAfter(SHUTDOWN_WAIT_MILLIS) {
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
            if (!clean) logger.warning("Tracel stopped waiting for its last writes after $SHUTDOWN_WAIT_MILLIS ms; the rest still goes")
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
