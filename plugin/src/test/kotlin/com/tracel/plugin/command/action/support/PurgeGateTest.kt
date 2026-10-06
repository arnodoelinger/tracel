package com.tracel.plugin.command.action.support

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class PurgeGateTest {
    private val rollback = AtomicBoolean(false)
    private val gate = PurgeGate { rollback.get() }

    @Test
    fun `a slice runs at once when no rollback is running`() = runTest {
        var ran = 0
        gate.slice { ran++ }
        assertEquals(1, ran)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a slice waits for a rollback to finish, says so once, and then runs`() = runTest {
        rollback.set(true)
        var waits = 0
        var ran = false
        val job = launch { gate.slice(onWait = { waits++ }) { ran = true } }

        advanceTimeBy(5_000.milliseconds)
        runCurrent()
        assertFalse(ran, "not under a rollback")
        assertEquals(1, waits, "one word about it, not one per poll")

        rollback.set(false)
        advanceTimeBy(1_000.milliseconds)
        job.join()
        assertTrue(ran)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a rollback that starts mid-purge holds the next slice and waits only for the running one`() = runTest {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { gate.slice { inside.complete(Unit); release.await() } }
        inside.await()

        rollback.set(true)
        val waited = async { gate.awaitSlice(); true }
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertFalse(waited.isCompleted, "the slice under way is the one thing a rollback waits for")

        release.complete(Unit)
        first.join()
        advanceTimeBy(100.milliseconds)
        assertTrue(waited.await())

        var next = false
        val second = launch { gate.slice { next = true } }
        advanceTimeBy(3_000.milliseconds)
        runCurrent()
        assertFalse(next, "no slice starts while the rollback holds the gate")

        rollback.set(false)
        advanceTimeBy(1_000.milliseconds)
        second.join()
        assertTrue(next)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a rollback never waits for a purge that is between slices`() = runTest {
        gate.slice { }
        val start = testScheduler.currentTime
        gate.awaitSlice()
        assertEquals(start, testScheduler.currentTime)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `awaitIdle is free when nothing runs and polls when something does`() = runTest {
        gate.awaitIdle { error("nothing to wait for") }

        rollback.set(true)
        var told = false
        val job = launch { gate.awaitIdle { told = true } }
        advanceTimeBy(1_500.milliseconds)
        runCurrent()
        assertTrue(told)
        assertFalse(job.isCompleted)
        rollback.set(false)
        advanceTimeBy(600.milliseconds)
        job.join()
        delay(1.milliseconds)
    }
}
