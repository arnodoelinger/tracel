package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.ledger.LotLedger
import com.tracel.model.id.Quantity
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.log.SqliteTransactionLog
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.HdrHistogram.Histogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.system.measureNanoTime

/** Ledger throughput test. */
class LedgerThroughputTest {
    @Test
    fun `captures run faster batched into one unit of work than one commit at a time`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.storage)
            val ledger = LotLedger(repo)
            val counters = SqliteCounters(db.storage)
            val capture = CaptureCoordinator(ledger, SqliteTransactionLog(db.storage), counters::nextTxnId, counters::nextSeq)

            val chest = block(0, 64, 0)
            val steve = player(1)
            ledger.mint(chest, diamond, Quantity(WARMUP + SAMPLES * 2L), counters.nextTxnId())

            // Don't remove this
            repeat(WARMUP) { move(capture, chest, steve) }

            val batched = Histogram(3)
            val perCall = Histogram(3)

            repeat(SAMPLES / BATCH) {
                repeat(BATCH) { perCall.recordValue(measureNanoTime { move(capture, chest, steve) }) }
                val batchNanos = measureNanoTime { ledger.atomically { repeat(BATCH) { move(capture, chest, steve) } } }
                repeat(BATCH) { batched.recordValue(batchNanos / BATCH) }
            }

            report("one commit per capture", perCall)
            report("one commit per unit of work", batched)

            assertEquals(
                WARMUP + SAMPLES * 2L,
                ledger.totalAt(steve, diamond)?.raw,
                "every measured capture must actually have moved a diamond",
            )
            assertTrue(
                batched.getValueAtPercentile(50.0) <= perCall.getValueAtPercentile(50.0),
                "batching a capture into one unit of work must not be slower than committing per call: " +
                    "${batched.getValueAtPercentile(50.0)}ns vs ${perCall.getValueAtPercentile(50.0)}ns",
            )
        }
    }

    private suspend fun move(capture: CaptureCoordinator, from: com.tracel.model.holder.HolderId, to: com.tracel.model.holder.HolderId) {
        capture.record(
            listOf(InventoryDelta(from, diamond, -1L), InventoryDelta(to, diamond, 1L)),
            epochMillis = 0L,
            cause = CauseKind.HOPPER,
            causedBy = null,
        )
    }

    private fun report(label: String, histogram: Histogram) {
        println(
            "$label: p50=${histogram.getValueAtPercentile(50.0) / 1_000}us " +
                "p99=${histogram.getValueAtPercentile(99.0) / 1_000}us " +
                "max=${histogram.maxValue / 1_000}us"
        )
    }

    private companion object {
        const val WARMUP = 200
        const val SAMPLES = 300
        const val BATCH = 10
    }
}
