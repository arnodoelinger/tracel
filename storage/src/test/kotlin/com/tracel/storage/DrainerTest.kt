package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.model.id.Quantity
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class DrainerTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    private suspend fun seed(stack: Stack, quantity: Long) {
        stack.ledger.mint(chest, diamond, Quantity(quantity), stack.counters.nextTxnId())
    }

    @Test
    fun `a whole batch of events becomes one unit of work`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            seed(stack, 500)
            repeat(400) { stack.gate.move(CauseKind.HOPPER, null, 1000L + it, diamond, chest, steve, 1) }

            val before = stack.storage.engine.stats().writes
            assertEquals(400, stack.drain())
            val commits = stack.storage.engine.stats().writes - before

            assertEquals(400L, stack.ledger.totalAt(steve, diamond)?.raw)
            assertTrue(commits <= 4, "400 events must not cost 400 commits, took $commits")
        }
    }

    @Test
    fun `one impossible event does not take the rest of its batch with it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            seed(stack, 10)

            stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 3)
            // Nothing anywhere holds a diamond block, so this withdrawal cannot be satisfied.
            stack.gate.move(CauseKind.HOPPER, null, 2L, diamondBlock, chest, steve, 99)
            stack.gate.move(CauseKind.HOPPER, null, 3L, diamond, chest, steve, 4)

            stack.drain()

            assertEquals(7L, stack.ledger.totalAt(steve, diamond)?.raw, "both possible moves must have landed")
            assertEquals(null, stack.ledger.totalAt(steve, diamondBlock), "the impossible one must have landed nowhere")
            assertEquals(1L, stack.drainer.refused)
            assertEquals(2L, stack.drainer.events)
        }
    }

    @Test
    fun `the drain loop stops when its scope is cancelled`(@TempDir dir: Path) = runTest {
        // Real time, not the test scheduler's: the drainer runs on a real thread of its own, and
        // a virtual clock would time out ten seconds into a wait that has not happened yet.
        withContext(Dispatchers.Default) {
        Stack(dir).use { stack ->
            seed(stack, 100)
            val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)
            val job: Job = stack.drainer.start(scope)

            repeat(50) { stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 1) }
            withTimeout(10_000.milliseconds) {
                while (stack.drainer.events < 50L) delay(5.milliseconds)
            }

            job.cancel()
            withTimeout(10_000.milliseconds) { job.join() }
            assertTrue(job.isCancelled, "cancellation has to actually work")

            val settled = stack.drainer.events
            stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 1)
            delay(100.milliseconds)
            assertEquals(settled, stack.drainer.events)
            scope.cancel()
        }
        }
    }

    @Test
    fun `readers see a consistent view while the drainer is writing`(@TempDir dir: Path) = runTest {
        withContext(Dispatchers.Default) {
        Stack(dir).use { stack ->
            seed(stack, 5_000)
            val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)
            stack.drainer.start(scope)

            try {
                coroutineScope {
                    val writer = async {
                        repeat(3_000) {
                            stack.gate.move(CauseKind.HOPPER, null, System.currentTimeMillis(), diamond, chest, steve, 1)
                            if (it % 200 == 0) delay(1.milliseconds)
                        }
                    }
                    val readers = (1..4).map {
                        async {
                            var reads = 0
                            while (reads < 100) {
                                // The invariant every read must see, at every instant: what Steve
                                // holds plus what the chest holds is always exactly what was minted.
                                val total = stack.storage.atomically {
                                    (stack.ledger.totalAt(steve, diamond)?.raw ?: 0L) +
                                        (stack.ledger.totalAt(chest, diamond)?.raw ?: 0L)
                                }
                                assertEquals(5_000L, total, "a reader saw a torn intermediate state")
                                reads++
                                delay(1.milliseconds)
                            }
                            reads
                        }
                    }
                    (listOf(writer) + readers).awaitAll()
                }
            } finally {
                scope.cancel()
            }
        }
        }
    }

    @Test
    fun `a captured event reaches the log as a queryable transaction`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            seed(stack, 100)
            stack.gate.move(CauseKind.HOPPER, steve, 1_700_000_000_000L, diamond, chest, steve, 5)
            stack.drain()

            val found = stack.log.query(LookupFilter(holders = setOf(steve)))
            val move = found.first { it.cause == CauseKind.HOPPER }
            assertEquals(1_700_000_000_000L, move.epochMillis)
            assertEquals(steve, move.causedBy)
            assertEquals(5L, move.flows.single().quantity.raw)
            assertEquals(chest, move.flows.single().source)
            assertEquals(steve, move.flows.single().destination)
        }
    }
}
