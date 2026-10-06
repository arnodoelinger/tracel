package com.tracel.storage.codec

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class KeysTest {
    private fun declaredTags(): Map<String, Byte> = Keys::class.java.declaredFields
        .filter { it.type == java.lang.Byte.TYPE }
        .filterNot { it.name.startsWith("PROGRESS_") || it.name.startsWith("NS_") }
        .associate { it.isAccessible = true; it.name to it.getByte(Keys) }

    @Test
    fun `every declared family is accounted for`() {
        val missing = declaredTags().filterValues { it !in Keys.ALL.toList() }
        assertTrue(
            missing.isEmpty(),
            "these key families are declared but missing from Keys.ALL, so a purge would leave " +
                    "their records behind for ever: ${missing.keys}",
        )
    }

    @Test
    fun `the numbering is not purgeable`() {
        for (kept in Keys.KEEPS_ITS_NUMBERING) {
            assertTrue(kept in Keys.ALL.toList(), "a kept family still has to be a declared one")
        }
        assertTrue(Keys.COUNTER in Keys.KEEPS_ITS_NUMBERING.toList(), "the counters outlive a purge")
        assertTrue(Keys.INTERN_FORWARD in Keys.KEEPS_ITS_NUMBERING.toList(), "so does interning")
        assertTrue(Keys.INTERN_REVERSE in Keys.KEEPS_ITS_NUMBERING.toList(), "both directions of it")
    }

    @Test
    fun `spatial keys run newest-first inside one chunk`() {
        val older = Keys.spatial(1, 0, 4, 70, 1, epochMillis = 1_000)
        val newer = Keys.spatial(1, 0, 4, 70, 2, epochMillis = 4_000_000)
        assertTrue(Arrays.compareUnsigned(newer, older) < 0, "inverted millis: newer first")
        assertEquals(4, Keys.spatialChunkZ(newer))
        assertEquals(0, Keys.spatialChunkX(newer))
        assertEquals(4_000_000L, Keys.spatialMillis(newer))
        assertEquals(1_000L, Keys.spatialMillis(older))
        assertEquals(Keys.SPATIAL_SIZE, newer.size)

        val chunk = Keys.spatialChunkPrefix(1, 0, 4)
        assertTrue(newer.copyOf(chunk.size).contentEquals(chunk))
        assertFalse(Keys.spatial(1, 0, 5, 70, 3, 4_000_000).copyOf(chunk.size).contentEquals(chunk))

        val from = Keys.spatialChunkFrom(1, 0, 4, untilMillis = 4_000_000)
        assertTrue(Arrays.compareUnsigned(from, newer) <= 0, "the row at `until` is included")
        assertTrue(Arrays.compareUnsigned(from, Keys.spatial(1, 0, 4, 70, 3, 4_000_001)) > 0)
    }

    @Test
    fun `no two families share a tag`() {
        val tags = declaredTags()
        assertEquals(tags.size, tags.values.distinct().size, "two key families share a tag byte: $tags")
        assertEquals(Keys.ALL.size, Keys.ALL.distinct().size, "Keys.ALL lists a family twice")
    }
}
