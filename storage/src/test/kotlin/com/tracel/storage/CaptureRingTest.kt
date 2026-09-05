package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureRingTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    private suspend fun warm(stack: Stack) {
        stack.ledger.mint(chest, diamond, com.tracel.model.id.Quantity(10_000_000), stack.counters.nextTxnId())
        stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 1)
        stack.drain()
    }

    @Test
    fun `an event survives the ring intact`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            warm(stack)
            val before = stack.ledger.totalAt(steve, diamond)?.raw ?: 0L

            assertTrue(stack.gate.move(CauseKind.HOPPER, null, 5L, diamond, chest, steve, 7))
            assertEquals(1, stack.drain())
            assertEquals(before + 7, stack.ledger.totalAt(steve, diamond)?.raw)
        }
    }

    @Test
    fun `a release resolves what the holder actually had, on the storage thread`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            warm(stack)
            stack.gate.move(CauseKind.HOPPER, null, 5L, diamond, chest, steve, 20)
            stack.drain()

            assertTrue(stack.gate.release(CauseKind.WORLD, null, 6L, steve, chest))
            assertEquals(1, stack.drain())
            assertEquals(null, stack.ledger.totalAt(steve, diamond), "everything Steve held moved back")
        }
    }

    @Test
    fun `a full ring drops rather than waits, and counts what it dropped`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir, ringSlots = 16).use { storage ->
            val gate = com.tracel.storage.capture.CaptureGate(storage.ring)
            var accepted = 0
            repeat(100) { if (gate.move(CauseKind.HOPPER, null, it.toLong(), diamond, chest, steve, 1)) accepted++ }

            assertEquals(5, accepted, "16 slots hold five three-slot events")
            assertEquals(95L, gate.dropped, "every refusal is counted, none of them waited")
        }
    }

    @Test
    fun `the ring drains in order under many concurrent producers`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            warm(stack)

            val producers = 8
            val each = 500
            val start = CountDownLatch(1)
            val done = CountDownLatch(producers)
            val threads = (0 until producers).map { p ->
                Thread {
                    start.await()
                    repeat(each) { i -> stack.gate.move(CauseKind.HOPPER, null, (p * each + i).toLong(), diamond, chest, steve, 1) }
                    done.countDown()
                }.apply { isDaemon = true; startupName(p); start() }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "producers must never block on a ring")
            threads.forEach { it.join(1000) }

            var drained = 0
            repeat(200) {
                drained += stack.drain()
                if (drained >= producers * each) return@repeat
            }
            assertEquals(producers * each, drained, "every published event must be drained exactly once")
            assertEquals(0L, stack.gate.dropped)
        }
    }

    @Test
    fun `a steady-state enqueue allocates nothing on the calling thread`(@TempDir dir: Path) = runTest {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(
            threads != null && threads.isThreadAllocatedMemorySupported,
            "this JVM cannot measure per-thread allocation",
        )
        requireNotNull(threads)
        threads.isThreadAllocatedMemoryEnabled = true

        Stack(dir).use { stack ->
            warm(stack)

            var allocated = -1L
            val worker = Thread {
                val id = Thread.currentThread().threadId()
                repeat(2_000) { stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 1) }
                val before = threads.getThreadAllocatedBytes(id)
                repeat(10_000) { stack.gate.move(CauseKind.HOPPER, null, 1L, diamond, chest, steve, 1) }
                allocated = threads.getThreadAllocatedBytes(id) - before
            }
            worker.start()
            worker.join()

            assertTrue(
                allocated < 10_000,
                "10 000 enqueues allocated $allocated bytes on the producer thread — the point of the ring is that this is zero",
            )
        }
    }

    private fun Thread.startupName(index: Int) {
        name = "capture-producer-$index"
    }
}
