package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.lsm.write.Wal
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.spi.WritesStopped
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.SyncFailedException
import java.nio.file.Path

class WritesStoppedTest {
    private fun key(i: Int): ByteArray = byteArrayOf(1) + ByteArray(8) { (i.toLong() ushr (56 - it * 8)).toByte() }
    private fun value(i: Int): ByteArray = "value-$i".toByteArray()

    private fun engine(dir: Path, memtable: Long = 64 * 1024, frozen: Int = 3) =
        LsmEngine(dir, LsmConfig(memtableBytes = memtable, maxFrozenMemtables = frozen, sync = SyncPolicy.EveryBatch))

    private fun LsmEngine.put(i: Int) = write(MutationBatch().apply { put(key(i), value(i)) }, durable = true)

    private fun LsmEngine.assertHas(range: IntRange) = snapshot().use { snapshot ->
        for (i in range) assertArrayEquals(value(i), snapshot.get(key(i))?.let { it.readBytes(0, it.byteSize().toInt()) }, "lost $i")
    }

    private fun awaitHealed(engine: LsmEngine) {
        val deadline = System.currentTimeMillis() + 20_000
        while (engine.stopped != null) {
            assertTrue(System.currentTimeMillis() < deadline, "the engine never took writes again")
            Thread.sleep(20)
        }
    }

    @Test
    fun `a log whose fsync failed never says it synced again`(@TempDir dir: Path) {
        var fail = true
        Wal.create(dir.resolve("a.log")) { if (fail) throw IOException("EIO") }.use { wal ->
            wal.append(1, MutationBatch().apply { put(key(1), value(1)) })
            assertThrows<IOException> { wal.sync() }
            fail = false
            assertThrows<IOException> { wal.sync() }
            assertThrows<IOException> { wal.append(2, MutationBatch().apply { put(key(2), value(2)) }) }
            wal.abandon()
        }
    }

    @Test
    fun `a failed fsync stops the writes after it, and healing brings them back`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            for (i in 0 until 100) engine.put(i)
            var fail = true
            engine.syncFault = { if (fail) throw SyncFailedException("sync failed") }

            engine.put(100)
            assertEquals(true, engine.stopped?.fatal)
            assertThrows<WritesStopped> { engine.put(101) }
            engine.assertHas(0..100)

            fail = false
            awaitHealed(engine)
            engine.put(101)
            engine.assertHas(0..101)
            engine.halt()
        }
        engine(dir).use { it.assertHas(0..101) }
    }

    @Test
    fun `a crash while stopped replays the broken log as the newest`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            for (i in 0 until 100) engine.put(i)
            engine.syncFault = { throw IOException("EIO") }
            engine.put(100)
            assertThrows<WritesStopped> { engine.put(101) }
            engine.halt()
        }
        engine(dir).use { engine ->
            engine.assertHas(0..100)
            engine.put(101)
        }
    }

    @Test
    fun `failing flushes stop the writes instead of piling memtables up`(@TempDir dir: Path) {
        val memtable = 16L * 1024
        engine(dir, memtable, frozen = 2).use { engine ->
            var fail = true
            engine.flushFault = { if (fail) throw IOException("ENOSPC") }

            var written = 0
            val stopped = assertThrows<WritesStopped> {
                while (written < 100_000) engine.put(written++)
            }
            assertEquals("ENOSPC", stopped.cause?.message)
            assertFalse(stopped.fatal, "a full disk is worth waiting for")
            assertTrue(
                engine.stats().memtableBytes <= memtable * (1 + 2) * 2,
                "memtables grew to ${engine.stats().memtableBytes} bytes while flushes failed",
            )
            val last = written - 2
            engine.assertHas(0..last)

            fail = false
            awaitHealed(engine)
            engine.put(written)
            engine.assertHas(0..last)
            engine.halt()
            engine(dir, memtable, frozen = 2).use { reopened -> reopened.assertHas(0..last) }
        }
    }
}
