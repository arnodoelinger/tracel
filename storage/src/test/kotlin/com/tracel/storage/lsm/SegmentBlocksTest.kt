package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.read.SegmentRun
import com.tracel.storage.lsm.segment.SegmentFile
import com.tracel.storage.lsm.segment.SegmentMeta
import com.tracel.storage.lsm.segment.SegmentReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import kotlin.random.Random

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

    @Test
    fun `index rows go to disk as planes and read back the same`(@TempDir dir: Path) {
        fun indexKey(i: Int): ByteArray {
            val position = i / 7
            val seq = (i * 2_654_435_761L) and 0xFFFFFF
            return byteArrayOf(0x18, 0, 0, 0, 1) + ByteArray(12) { (position ushr ((11 - it) * 4)).toByte() } +
                ByteArray(8) { ((Long.MAX_VALUE - seq) ushr (56 - it * 8)).toByte() }
        }

        val keys = (0 until rows).map(::indexKey).distinctBy { it.toList() }.sortedWith(Arrays::compareUnsigned)
        fun written(version: Int): SegmentReader {
            val path = dir.resolve("index-v$version.seg")
            val meta = SegmentFile.Writer(path, keys.size, version = version).use { writer ->
                for ((i, key) in keys.withIndex()) writer.add(internal(key), if (i % 11 == 0) null else ByteArray(0))
                writer.finish(1, 0)
            }
            return SegmentFile.open(path, meta)
        }

        written(SegmentFile.PLANES_SINCE - 1).use { rows ->
            written(SegmentFile.VERSION).use { planes ->
                assertTrue(planes.meta.fileBytes < rows.meta.fileBytes, "${planes.meta.fileBytes} against ${rows.meta.fileBytes}")
                val bytes = Files.readAllBytes(dir.resolve("index-v${SegmentFile.VERSION}.seg"))
                val flags = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
                assertTrue(flags and SegmentFile.FLAG_PLANES != 0, "first block is not planes")

                val a = SegmentRun(rows).apply { seek(ByteArray(0)) }
                val b = SegmentRun(planes).apply { seek(ByteArray(0)) }
                var count = 0
                while (a.valid) {
                    assertTrue(b.valid, "row $count")
                    assertArrayEquals(a.userKeyBytes(), b.userKeyBytes(), "row $count")
                    assertEquals(a.isDeletion(), b.isDeletion(), "row $count")
                    a.next()
                    b.next()
                    count++
                }
                assertFalse(b.valid)
                assertEquals(keys.size, count)
                for (key in keys.shuffled(Random(7)).take(500)) {
                    b.seek(InternalKey.seekTarget(key))
                    assertArrayEquals(key, b.userKeyBytes())
                }
            }
        }
    }
}
