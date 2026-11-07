package com.tracel.storage.chrono.hash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HashTest {
    @Test
    fun `IntIndex numbers keys in arrival order and ranks them`() {
        val random = Random(1)
        val index = IntIndex(2)
        val seen = LinkedHashSet<Int>()
        repeat(5000) {
            val key = random.nextInt(-3000, 3000)
            val i = index.add(key)
            if (seen.add(key)) assertEquals(seen.size - 1, i) else assertEquals(seen.indexOf(key), i)
        }
        assertEquals(seen.size, index.size)
        assertEquals(-1, index.indexOf(999_999))
        val ranked = index.ranked()
        val sorted = seen.sorted()
        assertEquals(sorted, ranked.sorted.toList())
        seen.forEachIndexed { i, key -> assertEquals(key, ranked.sorted[ranked.rank[i]]) }
    }

    @Test
    fun `LongMap agrees with a HashMap through heavy churn`() {
        val random = Random(2)
        val map = LongMap<String>()
        val model = HashMap<Long, String>()
        repeat(200_000) {
            val key = random.nextLong(0, 4000) * 0x9E3779B97F4A7C15uL.toLong()
            when (random.nextInt(3)) {
                0, 1 -> assertEquals(model.put(key, "v$it"), map.put(key, "v$it"))
                else -> assertEquals(model.remove(key), map.remove(key))
            }
        }
        assertEquals(model.size, map.size)
        for ((k, v) in model) assertEquals(v, map[k])
        var walked = 0
        map.forEach { k, v ->
            assertEquals(model[k], v)
            walked++
        }
        assertEquals(model.size, walked)
        assertNull(map[12345L])
    }
}
