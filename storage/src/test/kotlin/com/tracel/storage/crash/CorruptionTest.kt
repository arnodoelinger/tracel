package com.tracel.storage.crash

import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.LsmOptions
import com.tracel.storage.lsm.io.LocalDisk
import com.tracel.storage.lsm.version.Manifest
import com.tracel.storage.lsm.wal.WalSyncPolicy
import com.tracel.storage.lsm.wal.WriteAheadLog
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CorruptionTest {
    private fun key(i: Int) = byteArrayOf(1) + ByteArray(8) { (i.toLong() ushr (56 - it * 8)).toByte() }

    private fun engine(dir: Path) =
        LsmEngine(dir, LsmOptions(memtableBytes = 1 shl 20, sync = WalSyncPolicy.EveryBatch))

    @Test
    fun `a torn write-ahead log frame truncates the log rather than poisoning it`(@TempDir dir: Path) {
        engine(dir).let { engine ->
            repeat(50) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.halt()
        }

        // Half a frame, the way a power cut leaves one
        val wal = Files.list(dir)
            .use { stream -> stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.toList().single() }
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

        val wal = Files.list(dir)
            .use { stream -> stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.toList().single() }
        Files.write(wal, ByteArray(64) { 0x5A }, StandardOpenOption.APPEND)

        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 20) assertNotNull(
                    snapshot.get(key(i)),
                    "lost $i to a checksum that should have caught the garbage"
                )
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
        assertTrue(
            failure is IllegalArgumentException,
            "a segment from a newer build must not be guessed at, got $failure"
        )
    }

    @Test
    fun `a missing manifest does not make the engine delete the data`(@TempDir dir: Path) {
        engine(dir).use { engine ->
            repeat(20) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.flushNow()
        }
        Files.delete(dir.resolve(Manifest.NAME))

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "a lost manifest must stop the start, got $failure")
        val survivors = Files.list(Manifest.segmentsDirectory(dir)).use { stream ->
            stream.filter { it.toString().endsWith(Manifest.SEGMENT_SUFFIX) }.count()
        }
        assertTrue(survivors > 0, "the segments must still be on disk")
    }

    @Test
    fun `a fresh directory with only the first log still starts`(@TempDir dir: Path) {
        Files.createDirectories(Manifest.segmentsDirectory(dir))
        Files.createFile(Manifest.walPath(dir, 1))
        engine(dir).close()
    }

    private fun twoLogs(dir: Path): Pair<Path, Path> {
        Files.createDirectories(Manifest.segmentsDirectory(dir))
        Manifest(lastSequence = 0, nextFileId = 3, walIds = listOf(1, 2), segments = emptyList()).write(dir, LocalDisk)
        val paths = listOf(1L, 2L).map { Manifest.walPath(dir, it) }
        paths.forEachIndexed { n, path ->
            WriteAheadLog.create(path, LocalDisk).use { wal ->
                repeat(5) { i ->
                    val at = n * 5 + i
                    wal.append(at + 1L, MutationBatch().apply { put(key(at), "value-${at % 10}".toByteArray()) })
                }
                wal.sync()
            }
        }
        return paths[0] to paths[1]
    }

    private fun flip(path: Path, at: Long) {
        val bytes = Files.readAllBytes(path)
        bytes[at.toInt()] = (bytes[at.toInt()].toInt() xor 0x01).toByte()
        Files.write(path, bytes)
    }

    @Test
    fun `damage in an older log stops the start instead of leaving a gap`(@TempDir dir: Path) {
        val (older, newer) = twoLogs(dir)
        flip(older, Files.size(older) / 5 * 2 + 20)

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "a gap must stop the start, got $failure")
        assertTrue(Files.exists(older) && Files.exists(newer), "refusing must not delete the logs")
    }

    @Test
    fun `a torn newest log is still just a crash when an older one is whole`(@TempDir dir: Path) {
        val (_, newer) = twoLogs(dir)
        FileChannel.open(newer, StandardOpenOption.WRITE).use { it.truncate(Files.size(newer) - 9) }

        engine(dir).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until 9) assertNotNull(snapshot.get(key(i)), "key $i")
                assertNull(snapshot.get(key(9)), "only the torn frame may be lost")
            }
        }
    }

    @Test
    fun `an older log that is torn is not a crash`(@TempDir dir: Path) {
        val (older, _) = twoLogs(dir)
        FileChannel.open(older, StandardOpenOption.WRITE).use { it.truncate(Files.size(older) - 9) }

        val failure = runCatching { engine(dir).close() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "an older log was synced when it closed, got $failure")
    }

    private fun oneLog(dir: Path, batches: Int): Path {
        engine(dir).let { engine ->
            repeat(batches) { i ->
                MutationBatch().apply { put(key(i), "value-$i".toByteArray()) }.let { engine.write(it, durable = true) }
            }
            engine.halt()
        }
        return Files.list(dir)
            .use { stream -> stream.filter { it.toString().endsWith(Manifest.LOG_SUFFIX) }.toList().single() }
    }

    private fun LsmEngine.keptPrefix(of: Int): Int = snapshot().use { snapshot ->
        val kept = (0 until of).takeWhile { snapshot.get(key(it)) != null }.size
        for (i in kept until of) assertNull(snapshot.get(key(i)), "key $i came back after a gap at $kept")
        kept
    }

    @Test
    fun `a newest log that ends in zeros is a power cut, not damage`(@TempDir dir: Path) {
        val wal = oneLog(dir, 40)
        val bytes = Files.readAllBytes(wal)
        bytes.fill(0, bytes.size / 2, bytes.size)
        Files.write(wal, bytes)

        engine(dir).use { engine ->
            val kept = engine.keptPrefix(40)
            assertTrue(kept in 1 until 40, "kept $kept of 40")
        }
    }

    @Test
    fun `damage in the newest log starts from what is before it`(@TempDir dir: Path) {
        val wal = oneLog(dir, 40)
        flip(wal, Files.size(wal) / 2)

        engine(dir).use { engine ->
            val kept = engine.keptPrefix(40)
            assertTrue(kept in 1 until 40, "kept $kept of 40")
        }
    }
}
