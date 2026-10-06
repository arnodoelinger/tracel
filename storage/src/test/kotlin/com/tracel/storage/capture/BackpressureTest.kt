package com.tracel.storage.capture

import com.tracel.model.cause.CauseKind
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.CaptureGate
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.system.measureNanoTime

class BackpressureTest {
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `a full ring refuses without waiting, and never forgets a refusal`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir, ringSlots = 64, overflowSlots = 0).use { storage ->
            val gate = CaptureGate(storage.ring)
            val elapsed = measureNanoTime {
                repeat(10_000) { gate.move(CauseKind.MACHINE, null, it.toLong(), diamond, chest, steve, 1) }
            }
            assertTrue(elapsed < 500_000_000, "10 000 refused enqueues took ${elapsed / 1_000_000}ms — something waited")
            val dropped = gate.dropped
            assertTrue(dropped > 9_000, "a 64-slot ring must have refused most of 10 000 events")

            repeat(100) { gate.move(CauseKind.MACHINE, null, it.toLong(), diamond, chest, steve, 1) }
            assertTrue(gate.dropped >= dropped)
        }
    }

    @Test
    fun `what the ring accepted is exactly what the ledger applied`(@TempDir dir: Path) = runTest {
        Stack(dir, overflowSlots = 0).use { stack ->
            stack.mint(chest, diamond, 100_000)

            var accepted = 0L
            repeat(200_000) {
                if (stack.gate.move(CauseKind.MACHINE, null, it.toLong(), diamond, chest, steve, 1)) accepted++
                if (it % 4_096 == 0) stack.drainer.drainOnce()
            }
            stack.drain()

            assertTrue(stack.gate.dropped > 0, "200 000 events past a 64 Ki-slot ring must have overflowed it")
            assertEquals(
                accepted,
                stack.ledger.totalAt(steve, diamond)?.raw,
                "every accepted event must land, and no refused one may",
            )
            assertEquals(
                100_000L,
                stack.ledger.census(diamond),
                "dropping events must never create or destroy a unit — only fail to record a move",
            )
        }
    }

    @Test
    fun `a full ring keeps what it cannot hold, and the drain applies it after the ring`(@TempDir dir: Path) = runTest {
        Stack(dir, ringSlots = 64).use { stack ->
            stack.mint(chest, diamond, 100_000)

            var accepted = 0L
            repeat(300) {
                if (stack.gate.move(CauseKind.MACHINE, null, it.toLong(), diamond, chest, steve, 1)) accepted++
            }
            assertEquals(300L, accepted, "nothing was refused: what the ring could not hold waited outside it")
            assertEquals(0L, stack.gate.dropped, "and nothing was lost")

            assertEquals(300, stack.drain(), "the drain applied the ring, then what waited")
            assertEquals(300L, stack.ledger.totalAt(steve, diamond)?.raw)
            assertEquals(100_000L, stack.ledger.census(diamond))
        }
    }

    @Test
    fun `past the waiting room events are lost and counted`(@TempDir dir: Path) = runTest {
        Stack(dir, ringSlots = 64, overflowSlots = 90).use { stack ->
            stack.mint(chest, diamond, 100_000)

            var accepted = 0L
            repeat(100) {
                if (stack.gate.move(CauseKind.MACHINE, null, it.toLong(), diamond, chest, steve, 1)) accepted++
            }
            assertTrue(stack.gate.dropped > 0, "the ring and the waiting room together cannot hold 100 events")
            assertEquals(100L, accepted + stack.gate.dropped, "every event was either kept or counted as lost")

            stack.drain()
            assertEquals(accepted, stack.ledger.totalAt(steve, diamond)?.raw, "what was kept landed, what was lost did not")
        }
    }
}
