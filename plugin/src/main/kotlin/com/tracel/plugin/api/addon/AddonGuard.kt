package com.tracel.plugin.api.addon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/** A handler still running after this long is hung, and its addon goes. */
internal const val HANG_MILLIS = 5_000L

/** A handler the caller waits on that takes longer than this gets a warning. */
internal const val SLOW_MILLIS = 50L

/** A handler the caller waits on that takes longer than this gets its addon disabled. */
internal const val STALL_MILLIS = 1_000L

/** Calls queued for one addon at most; more than that are dropped, not buffered without end. */
internal const val LANE_CAPACITY = 4_096

private const val WATCH_MILLIS = 250L

/**
 * Keeps one addon's code from hurting `Tracel` plugin.
 *
 * What nobody waits on goes through [later]: one call at a time, in order, off every thread `Tracel` needs. A call
 * that hangs is reported with its stack and the addon is disabled; its thread cannot be killed, but it gets no more
 * work. What a caller must wait on goes through [now], timed: slow gets a warning, very slow disables the addon
 * entirely.
 */
internal class AddonGuard(
    private val addon: String,
    private val logger: Logger,
    private val trip: (String) -> Unit,
    private val hangMillis: Long = HANG_MILLIS,
    private val stallMillis: Long = STALL_MILLIS,
    private val excused: Boolean = false,
    lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + lane)
    private val calls = Channel<Pair<String, () -> Unit>>(LANE_CAPACITY)
    private val running = AtomicReference<Running?>()
    private val tripped = AtomicBoolean()
    private val warnedFull = AtomicBoolean()
    private val warnedSlow = AtomicBoolean()
    private val warnedStall = AtomicBoolean()

    @Volatile
    private var reported: Running? = null

    private class Running(val what: String, val thread: Thread, val since: Long)

    init {
        scope.launch {
            for ((what, work) in calls) {
                running.set(Running(what, Thread.currentThread(), System.nanoTime()))
                try {
                    work()
                } catch (failure: Throwable) {
                    logger.log(Level.SEVERE, "Addon \"$addon\" $what threw.", failure)
                } finally {
                    running.set(null)
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCH_MILLIS.milliseconds)
                val now = running.get() ?: continue
                val millis = (System.nanoTime() - now.since) / 1_000_000
                if (millis < hangMillis || now === reported) continue
                reported = now
                val stack = now.thread.stackTrace.joinToString("\n\tat ", prefix = "\n\tat ")
                fail("its ${now.what} has been running for $millis ms and still has not returned: $stack", true)
                if (tripped.get()) return@launch
            }
        }
    }

    /** Whether the addon was disabled for misbehaving. */
    val isTripped: Boolean get() = tripped.get()

    /**
     * Runs [work] later, in order with this addon's other calls;
     * dropped once the addon is tripped or full.
     */
    fun later(what: String, work: () -> Unit) {
        if (tripped.get()) return
        if (calls.trySend(what to work).isSuccess) return
        if (warnedFull.compareAndSet(false, true)) {
            logger.warning("Addon \"$addon\" is $LANE_CAPACITY calls behind; newer ones are dropped until it catches up.")
        }
    }

    /**
     * Runs [work] here and now, timed, and hands back what it returns or throws.
     *
     * Once the addon is tripped, nothing of it runs anymore.
     */
    fun <T> now(what: String, work: () -> T): T {
        if (tripped.get()) throw AddonTripped(addon)
        val start = System.nanoTime()
        try {
            return work()
        } finally {
            val millis = (System.nanoTime() - start) / 1_000_000
            when {
                millis >= stallMillis -> fail("its $what kept the caller waiting $millis ms.")
                millis >= SLOW_MILLIS && warnedSlow.compareAndSet(false, true) ->
                    logger.warning("Addon \"$addon\" $what took $millis ms; the caller waits on it, keep it quick.")
            }
        }
    }

    private fun fail(why: String, hang: Boolean = false) {
        if (excused) {
            if (hang || warnedStall.compareAndSet(false, true)) {
                logger.warning("Addon \"$addon\" would be thrown out, but the admin excused it: $why.")
            }
            return
        }
        if (!tripped.compareAndSet(false, true)) return
        logger.severe("Addon \"$addon\" is disabled: $why.")
        trip(why)
    }

    override fun close() {
        tripped.set(true)
        calls.close()
        scope.cancel()
    }
}

/** An addon that was disabled for misbehaving was asked to run again. */
internal class AddonTripped(addon: String) : IllegalStateException("Addon \"$addon\" was disabled for misbehaving.")
