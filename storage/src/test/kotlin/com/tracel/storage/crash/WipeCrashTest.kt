package com.tracel.storage.crash

import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.LsmEngine
import com.tracel.storage.spi.MutationBatch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A purge of everything leaves the numbering behind. It has to land in one commit with the wipe: a process killed
 * between the two used to leave an empty store without the numbering it was meant to keep.
 */
class WipeCrashTest {
    private val history = byteArrayOf(0x10, 1)
    private val numbering = byteArrayOf(0x11, 1)

    private fun batch(vararg keys: ByteArray) = MutationBatch().apply { keys.forEach { put(it, byteArrayOf(7)) } }

    private fun has(engine: LsmEngine, key: ByteArray): Boolean = engine.snapshot().use { it.get(key) != null }

    @Test
    fun `after a wipe that keeps rows, a kill leaves exactly the kept rows`(@TempDir dir: Path) {
        val first = LsmEngine(dir, LsmConfig())
        first.write(batch(history, numbering), durable = true)
        first.wipe(batch(numbering))
        first.halt()

        val second = LsmEngine(dir, LsmConfig())
        try {
            assertNull(second.snapshot().use { it.get(history) }, "the history is gone")
            assertNotNull(second.snapshot().use { it.get(numbering) }, "the numbering is not")
            second.write(batch(history), durable = true)
            assertEquals(true, has(second, history), "and the store still takes writes")
        } finally {
            second.close()
        }
    }

    @Test
    fun `a kill before the wipe is committed leaves the history exactly as it was`(@TempDir dir: Path) {
        val first = LsmEngine(dir, LsmConfig())
        first.write(batch(history, numbering), durable = true)
        first.wipeHook = { throw IllegalStateException("killed after the new log, before the manifest") }
        assertThrows<IllegalStateException> { first.wipe(batch(numbering)) }
        first.halt()

        val second = LsmEngine(dir, LsmConfig())
        try {
            assertEquals(true, has(second, history), "the history is still there")
            assertEquals(true, has(second, numbering))
        } finally {
            second.close()
        }
    }

    @Test
    fun `a wipe that keeps nothing still empties the store for good`(@TempDir dir: Path) {
        val first = LsmEngine(dir, LsmConfig())
        first.write(batch(history), durable = true)
        first.wipe()
        first.halt()

        val second = LsmEngine(dir, LsmConfig())
        try {
            assertEquals(false, has(second, history))
        } finally {
            second.close()
        }
    }
}
