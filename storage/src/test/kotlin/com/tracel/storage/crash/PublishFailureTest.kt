package com.tracel.storage.crash

import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.lsm.disk.Disk
import com.tracel.storage.lsm.state.Manifest
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random

class PublishFailureTest {
    private val root = Path.of("/tracel-power-cut/publish-failure")
    private val config = LsmConfig(memtableBytes = 64L shl 10, level0Fanout = 2, sync = SyncPolicy.EveryBatch)

    private fun key(i: Int) = byteArrayOf(1, (i shr 8).toByte(), i.toByte())
    private fun value(i: Int) = "value-$i".toByteArray()

    @Test
    fun `a merge whose manifest never landed leaves its inputs where that manifest says they are`() {
        val disk = PowerCutDisk(PowerCutDisk.Image.EMPTY, Random(1))
        val failing = object : Disk by disk {
            override fun replace(from: Path, to: Path) {
                if (Thread.currentThread().name.startsWith("Tracel-Storage-Compaction")) {
                    throw IOException("No space left on device")
                }
                disk.replace(from, to)
            }
        }

        val engine = LsmEngine(root, config, failing)
        for (round in 0 until 2) {
            for (i in round * 50 until round * 50 + 50) {
                engine.write(MutationBatch().apply { put(key(i), value(i)) }, durable = true)
            }
            engine.flushNow()
        }
        engine.quiesce()

        engine.snapshot().close()

        val listed = Manifest.read(root, disk).segments.map { Manifest.segmentPath(root, it.id) }
        assertTrue(listed.size >= 2, "both flushes are in the manifest, the merge is not")
        for (path in listed) assertTrue(disk.exists(path), "the manifest on disk names ${path.fileName}, which is gone")

        val image = disk.cut()
        engine.halt()
        LsmEngine(root, config, PowerCutDisk(image, Random(2))).use { reopened ->
            reopened.snapshot().use { snapshot ->
                for (i in 0 until 100) {
                    val found = snapshot.get(key(i))?.let { it.readBytes(0, it.byteSize().toInt()) }
                    assertArrayEquals(value(i), found, "lost $i")
                }
            }
        }
    }
}
