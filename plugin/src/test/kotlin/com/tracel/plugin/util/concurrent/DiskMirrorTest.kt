package com.tracel.plugin.util.concurrent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiskMirrorTest {
    private val disk = HashMap<String, Int>()
    private var failNext = false

    private fun mirror() = DiskMirror<String, Int>().apply {
        open(HashMap(disk)) { changes ->
            if (failNext) {
                failNext = false
                error("disk said no")
            }
            for ((key, value) in changes) if (value == null) disk.remove(key) else disk[key] = value
        }
    }

    @Test
    fun `starts from disk and writes only what changed`() = runTest {
        disk["kept"] = 1
        disk["gone"] = 2
        val map = mirror()
        assertEquals(1, map["kept"])

        map.put("new", 3)
        map.remove("gone")
        map.flush()

        assertEquals(mapOf("kept" to 1, "new" to 3), disk)
    }

    @Test
    fun `a failed write is retried, but never over something newer`() = runTest {
        val map = mirror()
        map.put("page", 4)
        failNext = true
        assertTrue(runCatching { map.flush() }.isFailure, "the failure reaches the drain")
        assertTrue(disk.isEmpty())
        map.flush()
        assertEquals(mapOf("page" to 4), disk, "the retry")

        map.put("page", 5)
        failNext = true
        runCatching { map.flush() }
        map.put("page", 6)
        map.flush()
        assertEquals(mapOf("page" to 6), disk, "the old 5 lost to the newer 6")
    }

    @Test
    fun `removing what is not there writes nothing`() = runTest {
        disk["other"] = 1
        val map = mirror()
        assertNull(map.remove("never"))
        map.flush()
        assertEquals(mapOf("other" to 1), disk)
    }

    @Test
    fun `forgetting drops what was waiting too`() = runTest {
        val map = mirror()
        map.put("a", 1)
        map.forgetAll()
        map.flush()

        assertTrue(disk.isEmpty())
        assertNull(map["a"])
    }
}
