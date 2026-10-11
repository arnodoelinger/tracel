package com.tracel.storage.ports.log

import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyWriter
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.RolledSet
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.format.FormatVersion
import com.tracel.storage.format.StoreFormat
import com.tracel.storage.util.eachRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.MemorySegment
import java.nio.file.Path

class RolledBackTest {
    private suspend fun TracelStorage.rows(tag: Byte): Int = read {
        var rows = 0
        eachRow(byteArrayOf(tag)) { rows++ }
        rows
    }

    @Test
    fun `a set comes back as it went in, runs or bitmap`() {
        val shapes = listOf(
            emptyList(),
            listOf(0),
            listOf(4095),
            (0 until RolledSet.SPAN).toList(),
            (100..900).toList() + (2000..2001).toList(),
            (0 until RolledSet.SPAN step 2).toList(),
            (0 until RolledSet.SPAN step 7).toList(),
        )
        for (shape in shapes) {
            val slots = LongArray(RolledSet.SPAN / 64)
            shape.forEach { RolledSet.add(slots, it) }
            val value = MemorySegment.ofArray(RolledSet.encode(77, slots))
            assertEquals(77, RolledSet.millis(value))
            assertArrayEquals(slots, RolledSet.slots(value), "${shape.size} slots")
            assertTrue(value.byteSize() <= 8 + RolledSet.SPAN / 8, "${shape.size} slots")
        }
    }

    @Test
    fun `a rollback of many records is a few rows`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            val rolled = RolledBack(storage)
            val seqs = LongArray(100_000) { 5_000L + it } + longArrayOf(9_000_000, 9_000_002)
            rolled.mark(job = 1, seqs = seqs, millis = 42)

            assertTrue(storage.rows(Keys.ROLLED_SET) <= 27, "${storage.rows(Keys.ROLLED_SET)} rows")
            val found = rolled.of(listOf(4_999, 5_000, 104_999, 105_000, 9_000_000, 9_000_001, 9_000_002))
            assertEquals(mapOf(5_000L to 42L, 104_999L to 42L, 9_000_000L to 42L, 9_000_002L to 42L), found)
        }
    }

    @Test
    fun `marking twice for one job adds up`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            val rolled = RolledBack(storage)
            rolled.mark(1, longArrayOf(10, 11), 5)
            rolled.mark(1, longArrayOf(12, 5_000), 5)
            assertEquals(setOf(10L, 11L, 12L, 5_000L), rolled.of(listOf(9, 10, 11, 12, 13, 5_000)).keys)
        }
    }

    @Test
    fun `undoing a rollback forgets its marks and nobody else's`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            val rolled = RolledBack(storage)
            rolled.mark(1, longArrayOf(10, 11, 12), 100)
            rolled.mark(2, longArrayOf(11, 12, 13), 200)
            assertEquals(mapOf(10L to 100L, 11L to 200L, 12L to 200L, 13L to 200L), rolled.of(listOf(10, 11, 12, 13)))

            rolled.restore(2)
            assertEquals(mapOf(10L to 100L, 11L to 100L, 12L to 100L), rolled.of(listOf(10, 11, 12, 13)))

            rolled.restore(1)
            assertTrue(rolled.of(listOf(10, 11, 12, 13)).isEmpty())
            assertEquals(0, storage.rows(Keys.ROLLED_SET))
            assertEquals(0, storage.rows(Keys.ROLLED_SET_JOB))
        }
    }

    @Test
    fun `a database from before sets keeps what was rolled back`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            fun old(seq: Long) = KeyWriter(9).tag(Keys.ROLLED).u64(seq).done()
            fun oldJob(job: Long, seq: Long) = KeyWriter(17).tag(Keys.ROLLED_JOB).u64(job).u64(seq).done()
            fun owner(job: Long, millis: Long) = recordBytes(16) {
                putI64(0, job)
                putI64(8, millis)
            }

            StoreFormat.ensure(storage, FormatVersion(1, 0), emptyList())
            val first = 3_000L until 53_000L
            val again = (52_990L until 53_000L) + 700_000L
            for (chunk in first.chunked(10_000)) {
                storage.write {
                    for (seq in chunk) {
                        put(old(seq), owner(1, 100))
                        put(oldJob(1, seq), ByteArray(0))
                    }
                }
            }
            storage.write {
                for (seq in again) {
                    put(old(seq), owner(2, 200))
                    put(oldJob(2, seq), ByteArray(0))
                }
            }

            val outcome = StoreFormat.ensure(storage)
            assertEquals(StoreFormat.CURRENT, outcome.to)
            assertTrue(outcome.migrated)

            assertEquals(0, storage.rows(Keys.ROLLED))
            assertEquals(0, storage.rows(Keys.ROLLED_JOB))
            val rolled = RolledBack(storage)
            val asked = listOf(2_999L, 3_000L, 30_000L, 52_989L, 52_990L, 52_999L, 53_000L, 700_000L)
            assertEquals(
                mapOf(3_000L to 100L, 30_000L to 100L, 52_989L to 100L, 52_990L to 200L, 52_999L to 200L, 700_000L to 200L),
                rolled.of(asked),
            )

            rolled.restore(2)
            assertEquals(setOf(3_000L, 30_000L, 52_989L), rolled.of(asked).keys)
            assertFalse(StoreFormat.ensure(storage).migrated)
        }
    }
}
