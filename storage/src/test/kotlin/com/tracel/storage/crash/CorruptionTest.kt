package com.tracel.storage.crash

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.spi.MutationBatch
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CorruptionTest {
    private fun key(i: Int) = byteArrayOf(1) + ByteArray(8) { (i.toLong() ushr (56 - it * 8)).toByte() }

    private fun engine(dir: Path) = LsmEngine(dir, LsmConfig(memtableBytes = 1 shl 20, sync = SyncPolicy.EveryBatch))

    @Test
    fun `a torn write-ahead log frame truncates the log rather than poisoning it`(@TempDir dir: Path) {
        engine(dir).let { engine ->
            repeat(50) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.halt()
        }

        // Half a frame, the way a power cut leaves one
        val wal = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.toList().single() }
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
        engine(dir).let { engine ->
            repeat(20) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.halt()
        }

        val wal = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.toList().single() }
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

        val segment = Files.list(Manifest.segmentsDirectory(dir)).use { stream ->
            stream.filter { it.toString().endsWith(Manifest.SEGMENT_SUFFIX) }.toList().single()
        }
        val bytes = Files.readAllBytes(segment)
        bytes[bytes.size - 8] = 99
        Files.write(segment, bytes)

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "a segment from a newer build must not be guessed at, got $failure")
    }
}
