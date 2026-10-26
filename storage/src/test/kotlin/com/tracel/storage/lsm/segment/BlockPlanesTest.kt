package com.tracel.storage.lsm.segment

import com.tracel.storage.lsm.InternalKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Arrays
import kotlin.random.Random

class BlockPlanesTest {
    @Test
    fun `every block comes back byte for byte`(@TempDir dir: Path) {
        val random = Random(42)
        repeat(20) { round ->
            val keys = List(2_000) {
                val width = if (round % 2 == 0) 13 else random.nextInt(1, 40)
                ByteArray(width) { random.nextInt(if (it < 4) 3 else 256).toByte() }
            }.distinctBy { it.toList() }.sortedWith(Arrays::compareUnsigned)
            val path = dir.resolve("$round.seg")
            val meta = SegmentFile.Writer(path, keys.size, blockTarget = 1 shl random.nextInt(6, 16), version = 2).use { writer ->
                for (key in keys) {
                    val trailer = InternalKey.trailer(random.nextLong(0, 1L shl 40), InternalKey.TYPE_VALUE)
                    val value = when (random.nextInt(4)) {
                        0 -> null
                        1 -> ByteArray(0)
                        else -> ByteArray(random.nextInt(0, 300)) { random.nextInt(8).toByte() }
                    }
                    writer.add(key + ByteArray(8) { (trailer ushr (56 - it * 8)).toByte() }, value)
                }
                writer.finish(1, 0)
            }
            SegmentFile.open(path, meta).use { reader ->
                var at = 0L
                while (at < reader.end()) {
                    val raw = reader.blockAt(at)
                    assertArrayEquals(raw, BlockPlanes.decode(BlockPlanes.encode(raw, raw.size)), "round $round block $at")
                    at = reader.nextBlock(at)
                }
            }
        }
    }
}
