package com.tracel.plugin.governor

import com.tracel.plugin.listener.support.guard.SelfManagedWorldGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import kotlin.coroutines.resume

/** Calls to [Throttle.spent] per clock read. A power of two. */
private const val CLOCK_EVERY = 32

/** A server tick, in milliseconds. */
private const val TICK_MILLIS = 50.0

/** How much of what is left of a tick one restore may take. */
private const val HEADROOM_SHARE = 0.8

/** Without a reading, take what a quiet region would give. */
private const val BLIND_MILLIS = 5.0

private const val NANOS_PER_MILLI = 1_000_000.0

/** A slice of work asked for with less than this left of the turn is given this much, so it always gets something done. */
private const val MIN_SLICE_NANOS = 1_000_000L

/**
 * How long a rollback may hold a region in one tick: never less than [minNanos], so it always makes progress, and
 * never more than [maxNanos]. A tick that stays under 50 ms costs no TPS, so these are limits on how long a tick may
 * get, not a share of the server.
 */
data class GovernorSettings(
    val minNanos: Long = 5_000_000L,
    val maxNanos: Long = 35_000_000L,
)

/**
 * The nanoseconds a restore may hold the tick before it hands the rest back.
 *
 * [mspt] is the region's own average tick time over the last five seconds, which includes what this restore took
 * itself; [ownMillis] is that, so what is left is everyone else's load.
 */
internal fun allowance(settings: GovernorSettings, mspt: Double?, ownMillis: Double = 0.0): Long {
    val millis = if (mspt == null || mspt.isNaN()) {
        BLIND_MILLIS
    } else {
        val others = (mspt - ownMillis).coerceAtLeast(0.0)
        (TICK_MILLIS - others).coerceAtLeast(0.0) * HEADROOM_SHARE
    }
    return (millis * NANOS_PER_MILLI).toLong().coerceIn(settings.minNanos, settings.maxNanos)
}

/**
 * Keeps a rollback from holding a region's tick: reads how loaded the region is, says how long a [Throttle] may run,
 * and parks it until the region's next tick.
 */
class TickGovernor(private val plugin: Plugin, val settings: GovernorSettings = GovernorSettings()) {
    /** Region thread only. Off one, `Folia` refuses, and the answer is the blind default. */
    fun allowanceNanos(ownNanos: Long = 0L): Long {
        val mspt = runCatching { Bukkit.getAverageTickTime() }.getOrNull()
        return allowance(settings, mspt, ownNanos / NANOS_PER_MILLI)
    }

    /** Resumes on the region that owns the chunk, one tick from now. */
    internal suspend fun nextTick(world: World, chunkX: Int, chunkZ: Int) {
        suspendCancellableCoroutine { continuation ->
            val task = runCatching {
                Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, { continuation.resume(Unit) }, 1L)
            }.getOrElse {
                // Plugin shutting down: nothing will ever tick this, so carry on and let cancellation land
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }
}

/**
 * One restore's turns on a region thread. [enter] opens a turn and raises the self-write flag, [spent] says when the
 * turn has had its allowance, [nextTick] closes it, waits for the next tick and opens another.
 *
 * The flag is dropped between turns: the region runs everyone else's events in the gap, and those are not ours.
 */
internal class Throttle(
    private val governor: TickGovernor,
    private val guard: SelfManagedWorldGuard,
    private val world: World,
    private val chunkX: Int,
    private val chunkZ: Int,
) {
    private var deadline = 0L
    private var previous = false
    private var inTurn = false
    private var enteredAt = 0L
    private var lastTurnNanos = 0L
    private var asked = 0

    /** Opens a turn and raises the self-write flag. */
    fun enter() {
        previous = guard.enter()
        enteredAt = System.nanoTime()
        deadline = enteredAt + governor.allowanceNanos(lastTurnNanos)
        inTurn = true
    }

    /** Closes a turn and restores the self-write flag. */
    fun leave() {
        if (!inTurn) return
        lastTurnNanos = System.nanoTime() - enteredAt
        guard.leave(previous)
        inTurn = false
    }

    /**
     * Whether this turn has used up its allowance. Called between every block, so the clock is read only every few
     * calls.
     */
    fun spent(): Boolean {
        return ++asked and (CLOCK_EVERY - 1) == 0 && System.nanoTime() >= deadline
    }

    /** What is left of this turn's allowance, for work that sizes its own slice. Never less than [MIN_SLICE_NANOS]. */
    fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(MIN_SLICE_NANOS)

    /** Hands the tick back when the turn is spent; [afterHop] drops whatever the gap made stale. */
    suspend fun yieldIfSpent(afterHop: () -> Unit = {}) {
        if (!spent()) return
        nextTick()
        afterHop()
    }

    /** Hands the tick back and resumes on the region thread one tick from now. */
    suspend fun nextTick() {
        leave()
        governor.nextTick(world, chunkX, chunkZ)
        enter()
    }
}

/**
 * Runs [work] on the region thread already holding the chunk at [chunkX], [chunkZ], in turns the governor sizes: the
 * work calls [Throttle.yieldIfSpent] where it can stop. [guard] is the self-write flag the work needs raised, if any.
 */
internal suspend fun <T> TickGovernor.throttled(
    world: World,
    chunkX: Int,
    chunkZ: Int,
    guard: SelfManagedWorldGuard = SelfManagedWorldGuard(),
    work: suspend (Throttle) -> T,
): T {
    val throttle = Throttle(this, guard, world, chunkX, chunkZ)
    throttle.enter()
    try {
        return work(throttle)
    } finally {
        throttle.leave()
    }
}
