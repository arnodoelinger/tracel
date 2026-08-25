package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LsmEngineTest {
    private fun key(i: Int): ByteArray = byteArrayOf(1) + longBe(i.toLong())
    private fun longBe(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }
    private fun value(i: Int): ByteArray = "value-$i".toByteArray()

    private fun engine(dir: Path, memtable: Long = 64 * 1024) =
        LsmEngine(dir, LsmConfig(memtableBytes = memtable, sync = SyncPolicy.EveryBatch))

    @Test
    fun `round trips values through memtable and segments`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(5000) { i ->
                val batch = MutationBatch()
                batch.put(key(i), value(i))
                engine.write(batch, durable = false)
            }
            engine.snapshot().use { snapshot ->
                for (i in 0 until 5000) {
                    val got = snapshot.get(key(i)) ?: error("missing $i")
                    assertEquals("value-$i", String(got.readBytes(0, got.byteSize().toInt())))
                }
            }
            assertTrue(engine.stats().segmentCount > 0, "5000 writes into a 64KiB memtable must have flushed")
        }
    }

    @Test
    fun `deletes shadow older values across a flush`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            MutationBatch().apply { put(key(1), value(1)); put(key(2), value(2)) }
                .let { engine.write(it, durable = true) }
            engine.flushNow()
            MutationBatch().apply { delete(key(1)) }.let { engine.write(it, durable = true) }
            engine.flushNow()

            engine.snapshot().use { snapshot ->
                assertNull(snapshot.get(key(1)), "a tombstone must survive its own flush")
                assertEquals("value-2", snapshot.get(key(2))!!.let { String(it.readBytes(0, it.byteSize().toInt())) })
            }
        }
    }

    @Test
    fun `prefix scan returns keys in order and stops at the prefix`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            val batch = MutationBatch()
            repeat(100) { batch.put(key(it), value(it)) }
            batch.put(byteArrayOf(2) + longBe(0), value(999))
            engine.write(batch, durable = true)

            engine.snapshot().use { snapshot ->
                snapshot.scan(byteArrayOf(1)).use { cursor ->
                    var seen = 0
                    while (cursor.next()) {
                        assertEquals(1, cursor.key()[0])
                        seen++
                    }
                    assertEquals(100, seen)
                }
            }
        }
    }

    @Test
    fun `reopening replays the write-ahead log`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(200) { i ->
                MutationBatch().apply { put(key(i), value(i)) }.let { engine.write(it, durable = true) }
            }
        }
        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 200) {
                    val got = snapshot.get(key(i)) ?: error("lost $i across a reopen")
                    assertEquals("value-$i", String(got.readBytes(0, got.byteSize().toInt())))
                }
            }
        }
    }

    @Test
    fun `reopening after a flush reads out of segments`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(3000) { i ->
                MutationBatch().apply { put(key(i), value(i)) }.let { engine.write(it, durable = false) }
            }
            engine.flushNow()
        }
        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 3000) {
                    assertTrue(snapshot.get(key(i)) != null, "lost $i across flush + reopen")
                }
            }
            assertTrue(engine.stats().segmentCount > 0)
        }
    }

    @Test
    fun `overwrites win and compaction keeps them`(@TempDir dir: Path) {
        engine(dir, memtable = 32 * 1024).use { engine ->
            repeat(2000) { i ->
                MutationBatch().apply { put(key(i % 200), value(i)) }.let { engine.write(it, durable = false) }
            }
            engine.flushNow()
            engine.quiesce()
            engine.snapshot().use { snapshot ->
                for (k in 0 until 200) {
                    val expected = (1800..1999).first { it % 200 == k }
                    val got = snapshot.get(key(k)) ?: error("missing $k")
                    assertEquals("value-$expected", String(got.readBytes(0, got.byteSize().toInt())))
                }
            }
        }
    }
}
