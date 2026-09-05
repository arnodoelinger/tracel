package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.spi.MutationBatch
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LsmEngineTest {
    private fun key(i: Int): ByteArray = byteArrayOf(1) + longBe(i.toLong())
    private fun longBe(v: Long) = ByteArray(8) { (v ushr (56 - it * 8)).toByte() }
    private fun value(i: Int): ByteArray = "value-$i".toByteArray()

    private fun engine(dir: Path, memtable: Long = 64 * 1024) =
        LsmEngine(dir, LsmConfig(memtableBytes = memtable, sync = SyncPolicy.EveryBatch))

    @Test
    fun `a crash and restart leaves neither a giant log nor a giant memtable`(@TempDir dir: Path) {
        val memtable = 64L * 1024
        repeat(4) {
            engine(dir, memtable).let { engine ->
                repeat(4000) { i ->
                    val batch = MutationBatch()
                    batch.put(key(i), value(i))
                    engine.write(batch, durable = true)
                }
                assertTrue(
                    engine.stats().memtableBytes <= memtable * (1 + 3),
                    "memtable grew past its configured size plus the frozen queue",
                )
                engine.halt()
            }
            engine(dir, memtable).use { engine ->
                assertTrue(
                    engine.stats().memtableBytes <= memtable,
                    "recovery kept an oversized memtable: ${engine.stats().memtableBytes} bytes",
                )
                assertEquals(0L, walBytes(dir), "the replayed log was not retired")
                engine.snapshot().use { snapshot ->
                    for (i in 0 until 4000) assertNotNull(snapshot.get(key(i)), "recovery lost $i")
                }
            }
        }
    }

    @Test
    fun `a clean shutdown leaves nothing to replay`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(500) { i ->
                val batch = MutationBatch()
                batch.put(key(i), value(i))
                engine.write(batch, durable = true)
            }
        }
        assertEquals(0L, walBytes(dir), "a sealed shutdown must not leave a log behind")
        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 500) assertNotNull(snapshot.get(key(i)), "sealing on shutdown lost $i")
            }
        }
    }

    private fun walBytes(dir: Path): Long =
        java.nio.file.Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.mapToLong { java.nio.file.Files.size(it) }.sum()
        }

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

    @Test
    fun `a batch the fit check accepts always fits`(@TempDir dir: Path) {
        engine(dir, memtable = 64 * 1024).use { engine ->
            repeat(200) { round ->
                val batch = MutationBatch()
                repeat(300) { i -> batch.put(key(round * 300 + i), value(i)) }
                engine.write(batch, durable = false)
            }

            engine.snapshot().use { snapshot ->
                for (i in 0 until 200 * 300) assertNotNull(snapshot.get(key(i)), "lost key $i")
            }
        }
    }

    @Test
    fun `a store whose log outgrew the default memtable still reopens`(@TempDir dir: Path) {
        val big = MutationBatch()
        repeat(20_000) { i -> big.put(key(i), value(i)) }

        engine(dir, memtable = 16 * 1024).use { it.write(big, durable = true) }

        engine(dir, memtable = 16 * 1024).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 20_000) assertNotNull(snapshot.get(key(i)), "lost key $i after reopen")
            }
        }
    }

    @Test
    fun `keys handed out by a cursor outlive the cursor's position`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            for (round in 0 until 3) {
                repeat(300) { i ->
                    val batch = MutationBatch()
                    batch.put(key(i), value(i + round))
                    engine.write(batch, durable = false)
                }
                if (round < 2) engine.flushNow()
            }

            val kept = ArrayList<ByteArray>()
            val fields = ArrayList<Long>()
            engine.snapshot().use { snapshot ->
                snapshot.scan(byteArrayOf(1)).use { cursor ->
                    while (cursor.next()) {
                        kept += cursor.key()
                        fields += cursor.keyU64(1)
                        assertEquals(9, cursor.keyLength())
                        assertEquals(1.toByte(), cursor.keyByte(0))
                    }
                }
            }

            assertEquals(300, kept.size)
            for (i in 0 until 300) {
                assertArrayEquals(key(i), kept[i], "the cursor recycled a key it had handed out")
                assertEquals(i.toLong(), fields[i])
            }
        }
    }

    @Test
    fun `a scan whose prefix most segments know nothing about still reads every row`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            fun famKey(family: Int, i: Int) = byteArrayOf(family.toByte()) + longBe(i.toLong())
            for (family in 1..8) {
                repeat(400) { i ->
                    val batch = MutationBatch()
                    batch.put(famKey(family, i), "f$family-$i".toByteArray())
                    engine.write(batch, durable = false)
                }
                engine.flushNow()
            }

            for (family in 1..8) {
                val seen = ArrayList<Int>()
                engine.snapshot().use { snapshot ->
                    snapshot.scan(byteArrayOf(family.toByte())).use { cursor ->
                        while (cursor.next()) {
                            assertEquals(family.toByte(), cursor.keyByte(0))
                            seen += cursor.keyU64(1).toInt()
                        }
                    }
                }
                assertEquals((0 until 400).toList(), seen, "family $family came back wrong")
            }

            engine.snapshot().use { snapshot ->
                snapshot.scan(byteArrayOf(3)).use { cursor ->
                    cursor.skipTo(famKey(3, 350))
                    val rest = ArrayList<Int>()
                    while (cursor.next()) rest += cursor.keyU64(1).toInt()
                    assertEquals((350 until 400).toList(), rest)
                }
            }
        }
    }
}
