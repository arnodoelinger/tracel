package com.tracel.plugin.util.concurrent

import com.tracel.plugin.util.ExpiringMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class ExpiringMapFullTest {
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
