package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.read.SegmentRun
import com.tracel.storage.lsm.segment.SegmentFile
import com.tracel.storage.lsm.segment.SegmentMeta
import com.tracel.storage.lsm.segment.SegmentReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SegmentBlocksTest {
    private val rows = 5_000

    private fun userKey(i: Int): ByteArray = byteArrayOf(7) + ByteArray(8) { ((i * 2L) ushr (56 - it * 8)).toByte() }
    private fun between(i: Int): ByteArray = byteArrayOf(7) + ByteArray(8) { ((i * 2L + 1) ushr (56 - it * 8)).toByte() }
    private fun value(i: Int): ByteArray = "value-$i".repeat(1 + i % 5).toByteArray()

    private fun internal(userKey: ByteArray): ByteArray {
        val trailer = InternalKey.trailer(1, InternalKey.TYPE_VALUE)
        return userKey + ByteArray(8) { (trailer ushr (56 - it * 8)).toByte() }
    }

    private fun write(dir: Path, name: String, blockTarget: Int, version: Int = SegmentFile.VERSION): SegmentReader {
        val path = dir.resolve(name)
        val meta = SegmentFile.Writer(path, rows, blockTarget = blockTarget, version = version).use { writer ->
            for (i in 0 until rows) writer.add(internal(userKey(i)), value(i))
            writer.finish(1, 0)
        }
        return SegmentFile.open(path, meta)
    }

    private fun assertReadsWhole(reader: SegmentReader) {
        val run = SegmentRun(reader)
        run.seek(ByteArray(0))
        for (i in 0 until rows) {
            assertTrue(run.valid, "row $i")
            assertArrayEquals(userKey(i), run.userKeyBytes(), "row $i")
            val read = run.value()!!
            assertArrayEquals(value(i), read.readBytes(0, read.byteSize().toInt()), "row $i")
            run.next()
        }
        assertFalse(run.valid)
    }

    private fun assertSeeks(reader: SegmentReader) {
        val run = SegmentRun(reader)
        for (i in 0 until rows) {
            run.seek(InternalKey.seekTarget(userKey(i)))
            assertTrue(run.valid, "seek $i")
            assertArrayEquals(userKey(i), run.userKeyBytes(), "seek $i")

            // A key that is not there lands on the next one, and past the last one there is nothing
            run.seek(InternalKey.seekTarget(between(i)))
            if (i == rows - 1) {
                assertFalse(run.valid)
            } else {
                assertTrue(run.valid, "seek after $i")
                assertArrayEquals(userKey(i + 1), run.userKeyBytes(), "seek after $i")
            }
        }
    }

    @Test
    fun `a seek finds every key whatever the block size`(@TempDir dir: Path) {
        for (blockTarget in intArrayOf(64, 4096, SegmentFile.BLOCK_TARGET, 1 shl 20)) {
            write(dir, "$blockTarget.seg", blockTarget).use { reader ->
                assertTrue(reader.restarts)
                assertReadsWhole(reader)
                assertSeeks(reader)
            }
        }
    }

    @Test
    fun `a segment written before restart tables still reads`(@TempDir dir: Path) {
        write(dir, "old.seg", 4096, version = SegmentFile.OLDEST_VERSION).use { reader ->
            assertFalse(reader.restarts)
            assertReadsWhole(reader)
            assertSeeks(reader)
        }
    }

    @Test
    fun `bigger blocks make the same rows a smaller file`(@TempDir dir: Path) {
        val small = write(dir, "small.seg", 4096).use { it.meta.fileBytes }
        val big = write(dir, "big.seg", SegmentFile.BLOCK_TARGET).use { it.meta.fileBytes }
        assertTrue(big < small, "$big against $small")
    }

    @Test
    fun `a flipped byte in a block is reported, not read`(@TempDir dir: Path) {
        write(dir, "rot.seg", 4096).close()
        val path = dir.resolve("rot.seg")
        val bytes = Files.readAllBytes(path)
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        Files.write(path, bytes)

        val meta = SegmentMeta(1, 0, rows, bytes.size.toLong(), ByteArray(0), ByteArray(0))
        SegmentFile.open(path, meta).use { reader ->
            val failure = runCatching { assertReadsWhole(reader) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "rot must stop the read, got $failure")
        }
    }
}
