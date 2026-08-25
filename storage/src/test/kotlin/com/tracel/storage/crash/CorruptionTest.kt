package com.tracel.storage.crash

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.Manifest
import com.tracel.storage.lsm.SyncPolicy
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CorruptionTest {
    private fun key(i: Int) = byteArrayOf(1) + ByteArray(8) { (i.toLong() ushr (56 - it * 8)).toByte() }

    private fun engine(dir: Path) = LsmEngine(dir, LsmConfig(memtableBytes = 1 shl 20, sync = SyncPolicy.EveryBatch))

    @Test
    fun `a torn write-ahead log frame truncates the log rather than poisoning it`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(50) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
        }

        // Half a frame, the way a power cut leaves one
        val wal = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(".wal") }.toList().single() }
        val size = Files.size(wal)
        FileChannel.open(wal, StandardOpenOption.WRITE).use { it.truncate(size - 9) }

        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                assertNotNull(snapshot.get(key(0)), "everything before the torn frame must still be there")
                var found = 0
                for (i in 0 until 50) if (snapshot.get(key(i)) != null) found++
                assertTrue(found >= 49, "only the torn frame may be lost, got $found of 50")
            }
        }
    }

    @Test
    fun `garbage appended to the write-ahead log is ignored, not replayed`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(20) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
        }

        val wal = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(".wal") }.toList().single() }
        Files.write(wal, ByteArray(64) { 0x5A }, StandardOpenOption.APPEND)

        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 20) assertNotNull(snapshot.get(key(i)), "lost $i to a checksum that should have caught the garbage")
            }
        }
    }

    @Test
    fun `a corrupt manifest is refused loudly instead of being half-believed`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(20) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.flushNow()
        }

        val manifest = dir.resolve(Manifest.NAME)
        val bytes = Files.readAllBytes(manifest)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        Files.write(manifest, bytes)

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException || failure is IllegalStateException,
            "a manifest that fails its checksum must not be silently accepted, got $failure",
        )
    }

    @Test
    fun `a segment written by a future format version is refused`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(20) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.flushNow()
        }

        val segment = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(".seg") }.toList().single() }
        val bytes = Files.readAllBytes(segment)
        bytes[bytes.size - 8] = 99
        Files.write(segment, bytes)

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "a segment from a newer build must not be guessed at, got $failure")
    }

    @Test
    fun `a batch that never reached the log is simply not there`(@TempDir dir: Path) {
        val engine = engine(dir)
        MutationBatch().apply { put(key(1), "kept".toByteArray()) }.let { engine.write(it, durable = true) }
        // No close, no sync: this one is only in the page cache, which a kill would take with it
        MutationBatch().apply { put(key(2), "maybe".toByteArray()) }.let { engine.write(it, durable = false) }
        engine.close()

        engine(dir).use { reopened ->
            reopened.snapshot().use { snapshot ->
                assertNotNull(snapshot.get(key(1)))
                assertEquals(2, listOf(key(1), key(2)).count { snapshot.get(it) != null })
                assertNull(snapshot.get(key(3)))
            }
        }
    }
}
