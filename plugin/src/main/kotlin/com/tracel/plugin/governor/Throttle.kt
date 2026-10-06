package com.tracel.plugin.governor

import com.tracel.plugin.listener.support.guard.SelfManagedWorldGuard
import org.bukkit.World

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
