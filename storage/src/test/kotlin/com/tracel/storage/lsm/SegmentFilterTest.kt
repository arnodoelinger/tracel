package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SegmentFilterTest {
    private val rows = 20_000
    private val index = 9

    private fun key(tag: Int, i: Int): ByteArray = byteArrayOf(tag.toByte()) + ByteArray(8) { (i.toLong() ushr (56 - it * 8)).toByte() }
    private fun value(i: Int): ByteArray = "v$i".toByteArray()

    private fun filled(dir: Path, pointRead: (Int) -> Boolean): LsmEngine {
        val engine = LsmEngine(dir, LsmConfig(sync = SyncPolicy.Never, pointRead = pointRead))
        for (i in 0 until rows) {
            engine.write(MutationBatch().apply { put(key(7, i), value(i)); put(key(index, i), value(i)) }, durable = false)
        }
        engine.flushNow()
        return engine
    }

    @Test
    fun `keys left out of the filter are still found by a get`(@TempDir dir: Path) {
        filled(dir, pointRead = { it != index }).use { engine ->
            engine.snapshot().use { snapshot ->
                for (i in 0 until rows step 7) {
                    for (tag in intArrayOf(7, index)) {
                        val read = snapshot.get(key(tag, i))
                        assertNotNull(read, "tag $tag row $i")
                        assertArrayEquals(value(i), read!!.readBytes(0, read.byteSize().toInt()))
                    }
                }
                assertNull(snapshot.get(key(7, rows)))
                assertNull(snapshot.get(key(index, rows)))
            }
        }
    }

    @Test
    fun `a filter holds only the keys that are read by key`(@TempDir dir: Path) {
        val all = filled(dir.resolve("all"), pointRead = { true }).use { it.stats().liveBytes }
        val half = filled(dir.resolve("half"), pointRead = { it != index }).use { it.stats().liveBytes }
        val none = filled(dir.resolve("none"), pointRead = { false }).use { it.stats().liveBytes }
        val perKey = rows * 12 / 8
        assertEquals((all - half).toDouble(), perKey.toDouble(), perKey * 0.05)
        assertEquals((half - none).toDouble(), perKey.toDouble(), perKey * 0.05)
    }
}
