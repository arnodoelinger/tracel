package com.tracel.plugin.util.concurrent

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

private const val TTL = 30_000L

private fun stale() = System.currentTimeMillis() - TTL - 1

class ExpiringTest {
    @Test
    fun `a fresh entry comes back and a stale one does not`() {
        val map = ExpiringMap<String, Int>(TTL)
        map.put("fresh", 1)
        map.put("old", 2, now = stale())

        assertEquals(1, map["fresh"])
        assertNull(map["old"])
    }

    @Test
    fun `reading a stale entry is what takes it out`() {
        val map = ExpiringMap<String, Int>(TTL)
        map.put("old", 2, now = stale())
        assertEquals(1, map.size)

        assertNull(map["old"])
        assertEquals(0, map.size)
    }

    @Test
    fun `a full map refuses new keys and still refreshes the ones it has`() {
        val map = ExpiringMap<String, Int>(TTL, capacity = 1)
        assertTrue(map.put("kept", 1))

        assertFalse(map.put("turned away", 2))
        assertNull(map["turned away"])
        assertTrue(map.put("kept", 3))
        assertEquals(3, map["kept"])
    }

    @Test
    fun `putAll stamps every key with one moment`() {
        val map = ExpiringMap<Int, String>(TTL)
        map.putAll(1..3, "here")
        assertEquals(listOf("here", "here", "here"), (1..3).map { map[it] })

        map.putAll(4..6, "gone", now = stale())
        assertTrue((4..6).all { map[it] == null })
    }

    @Test
    fun `forEachFresh walks only what has not gone stale`() {
        val map = ExpiringMap<String, Int>(TTL)
        map.put("here", 1)
        map.put("gone", 2, now = stale())

        val seen = mutableListOf<String>()
        map.forEachFresh { key, _ -> seen += key }
        assertEquals(listOf("here"), seen)
    }

    @Test
    fun `remove hands back only what was still fresh`() {
        val map = ExpiringMap<String, Int>(TTL)
        map.put("here", 1)
        map.put("gone", 2, now = stale())

        assertEquals(1, map.remove("here"))
        assertNull(map.remove("here"))
        assertNull(map.remove("gone"))
        assertEquals(0, map.size)
    }

    @Test
    fun `putIfAbsent keeps what is fresh and replaces what is not`() {
        val map = ExpiringMap<String, Int>(TTL)
        map.put("held", 1)
        map.put("expired", 1, now = stale())

        assertFalse(map.putIfAbsent("held", 2))
        assertEquals(1, map["held"])

        assertTrue(map.putIfAbsent("expired", 2))
        assertEquals(2, map["expired"])

        assertTrue(map.putIfAbsent("new", 3))
        assertEquals(3, map["new"])
    }

    @Test
    fun `putIfAbsent respects the cap for keys it does not have`() {
        val map = ExpiringMap<String, Int>(TTL, capacity = 1)
        assertTrue(map.putIfAbsent("kept", 1))
        assertFalse(map.putIfAbsent("turned away", 2))
        assertNull(map["turned away"])
    }

    @Test
    fun `a set says yes once and no while the entry is still fresh`() {
        val seen = ExpiringSet<String>(TTL)

        assertTrue(seen.add("boat"))
        assertFalse(seen.add("boat"))
        assertTrue("boat" in seen)
        assertFalse("cart" in seen)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a bulk write far past capacity finishes and keeps to the capacity`() {
        val map = ExpiringMap<Int, Unit>(ttlMillis = 60_000L, capacity = 50_000)

        map.putAll(0 until 600_000, Unit)

        assertEquals(50_000, map.size)
        assertTrue(0 in map)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `single puts past capacity do not sweep the map every time`() {
        val map = ExpiringMap<Int, Unit>(ttlMillis = 60_000L, capacity = 50_000)
        for (key in 0 until 300_000) map.put(key, Unit)
        assertEquals(50_000, map.size)
    }
}
