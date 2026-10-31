package com.tracel.storage.codec

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class KeysTest {
    @Test
    fun `the numbering is not purgeable`() {
        assertTrue(Tag.COUNTER in Tag.KEEPS_NUMBERING, "the counters outlive a purge")
        assertTrue(Tag.INTERN_FORWARD in Tag.KEEPS_NUMBERING, "so does interning")
        assertTrue(Tag.INTERN_REVERSE in Tag.KEEPS_NUMBERING, "both directions of it")
    }

    @Test
    fun `spatial keys run newest-first inside one chunk`() {
        val older = Tag.SPATIAL[1, 0, 4, 1_000L, Keys.yBin(70), 1L]
        val newer = Tag.SPATIAL[1, 0, 4, 4_000_000L, Keys.yBin(70), 2L]
        assertTrue(Arrays.compareUnsigned(newer, older) < 0, "inverted millis: newer first")
        assertEquals(4, Tag.SPATIAL.int(newer, 2))
        assertEquals(0, Tag.SPATIAL.int(newer, 1))
        assertEquals(4_000_000L, Tag.SPATIAL.long(newer, 3))
        assertEquals(1_000L, Tag.SPATIAL.long(older, 3))
        assertEquals(30, newer.size)

        val chunk = Tag.SPATIAL[1, 0, 4]
        assertTrue(newer.copyOf(chunk.size).contentEquals(chunk))
        assertFalse(Tag.SPATIAL[1, 0, 5, 4_000_000L, Keys.yBin(70), 3L].copyOf(chunk.size).contentEquals(chunk))

        val from = Tag.SPATIAL[1, 0, 4, 4_000_000L]
        assertTrue(Arrays.compareUnsigned(from, newer) <= 0, "the row at `until` is included")
        assertTrue(Arrays.compareUnsigned(from, Tag.SPATIAL[1, 0, 4, 4_000_001L, Keys.yBin(70), 3L]) > 0)
    }

    @Test
    fun `no two families share a tag`() {
        val bytes = Tag.entries.map { it.byte }
        assertEquals(bytes.size, bytes.distinct().size, "two key families share a tag byte")
        for (tag in Tag.entries) assertSame(tag, Tag.of(tag.byte))
    }

    @Test
    fun `a prefix is the head of its key, and too many fields are refused`() {
        val key = Tag.ACTOR[7, 9L]
        assertEquals(13, key.size)
        assertTrue(key.copyOf(5).contentEquals(Tag.ACTOR[7]))
        assertTrue(key.copyOf(1).contentEquals(Tag.ACTOR.prefix))
        assertEquals(9L, Tag.ACTOR.long(key, 1))
        assertThrows(IllegalArgumentException::class.java) { Tag.ACTOR[7, 9L, 1] }
    }
}
