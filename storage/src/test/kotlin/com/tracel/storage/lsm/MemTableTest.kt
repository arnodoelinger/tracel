package com.tracel.storage.lsm

import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.write.MemTable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class MemTableTest {
    @Test
    fun `find agrees with a linear scan for every key and snapshot`() {
        val random = SplittableRandom(20260830)
        MemTable(8L * 1024 * 1024).use { table ->
            val widths = intArrayOf(4, 8, 8, 16, 24)
            val keys = ArrayList<ByteArray>()
            for (k in 0 until 250) {
                val family = random.nextInt(widths.size)
                keys += ByteArray(1 + widths[family]) { i ->
                    if (i == 0) family.toByte() else random.nextInt(3).toByte()
                }
            }

            var sequence = 1L
            val written = HashMap<String, MutableList<Pair<Long, ByteArray?>>>()
            repeat(4000) {
                val key = keys[random.nextInt(keys.size)]
                val deletion = random.nextInt(5) == 0
                val value = if (deletion) null else ByteArray(1 + random.nextInt(20)) { sequence.toByte() }
                assertTrue(table.put(key, value, sequence)) { "memtable filled up early" }
                written.getOrPut(key.joinToString(",")) { ArrayList() } += sequence to value
                sequence++
            }

            for (key in keys.distinctBy { it.joinToString(",") }) {
                val versions = written[key.joinToString(",")].orEmpty()
                for (snapshot in listOf(0L, 1L, sequence / 2, sequence - 1, sequence, Long.MAX_VALUE)) {
                    val expected = versions.filter { it.first <= snapshot }.maxByOrNull { it.first }
                    val node = table.find(key, snapshot)
                    if (expected == null) {
                        assertEquals(MemTable.NOT_FOUND, node) { "expected a miss for ${key.toList()} at $snapshot" }
                        continue
                    }
                    assertTrue(node != MemTable.NOT_FOUND) { "expected a hit for ${key.toList()} at $snapshot" }
                    assertArrayEquals(key, table.userKeyBytes(node))
                    assertEquals(expected.first, table.sequenceOf(node))
                    val value = expected.second
                    if (value == null) {
                        assertTrue(table.isDeletion(node))
                        assertNull(table.valueOf(node))
                    } else {
                        assertTrue(!table.isDeletion(node))
                        val held = table.valueOf(node)!!
                        assertArrayEquals(
                            value,
                            ByteArray(held.byteSize().toInt()) {
                                held.get(
                                    com.tracel.storage.ffm.Bytes.I8,
                                    it.toLong()
                                )
                            })
                    }
                }
            }
        }
    }

    @Test
    fun `level zero is sorted and holds every version`() {
        val random = SplittableRandom(7)
        MemTable(4L * 1024 * 1024).use { table ->
            var sequence = 1L
            var inserted = 0
            repeat(3000) {
                val key = ByteArray(1 + random.nextInt(20)) { random.nextInt(3).toByte() }
                if (table.put(key, ByteArray(4), sequence)) inserted++
                sequence++
            }
            assertEquals(inserted, table.entries)

            var node = table.seek(ByteArray(0))
            var walked = 0
            var previous: ByteArray? = null
            while (node != 0L) {
                val key = table.segment.let { segment ->
                    ByteArray(table.keyLength(node)) {
                        segment.get(
                            com.tracel.storage.ffm.Bytes.I8,
                            table.keyOffset(node) + it
                        )
                    }
                }
                previous?.let {
                    assertTrue(SegmentCompare.compare(table.segment, table.keyOffset(node), key.size, it) > 0) {
                        "level 0 is out of order at entry $walked"
                    }
                }
                previous = key
                walked++
                node = table.nextOf(node, 0)
            }
            assertEquals(inserted, walked)
        }
    }

    @Test
    fun `randomHeight stays inside the level cap over a million draws`() {
        MemTable(64L * 1024 * 1024).use { table ->
            val key = ByteArray(8)
            var sequence = 1L
            repeat(200_000) {
                assertTrue(table.put(key, null, sequence)) { "memtable filled up early at $sequence" }
                sequence++
            }
            assertEquals(200_000, table.entries)
            assertEquals(sequence - 1, table.maxSequence)
            val node = table.find(key, Long.MAX_VALUE)
            assertEquals(sequence - 1, table.sequenceOf(node))
        }
    }
}
