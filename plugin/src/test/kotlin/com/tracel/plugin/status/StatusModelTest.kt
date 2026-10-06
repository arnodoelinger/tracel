package com.tracel.plugin.status

import com.tracel.plugin.status.disk.DiskLevel
import com.tracel.plugin.status.health.HIGH_MSPT
import com.tracel.plugin.status.health.Health
import com.tracel.plugin.status.health.LAG_MILLIS
import com.tracel.plugin.status.rate.WriteRate
import com.tracel.plugin.status.rollback.RollbackSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StatusModelTest {
    private val gib = 1L shl 30

    @Test
    fun `a rollback is sized by the records it takes back`() {
        assertEquals(RollbackSize.TINY, RollbackSize.of(1_023))
        assertEquals(RollbackSize.SMALL, RollbackSize.of(1_024))
        assertEquals(RollbackSize.MEDIUM, RollbackSize.of(10_240))
        assertEquals(RollbackSize.LARGE, RollbackSize.of(102_400))
        assertEquals(RollbackSize.HUGE, RollbackSize.of(1_024_000))
        assertEquals(RollbackSize.HUGE, RollbackSize.of(Long.MAX_VALUE))
    }

    @Test
    fun `disk levels are shares of the disk`() {
        val total = 1000 * gib
        assertEquals(DiskLevel.OK, DiskLevel.of(50 * gib, total))
        assertEquals(DiskLevel.LOW, DiskLevel.of(50 * gib - 1, total))
        assertEquals(DiskLevel.LOW, DiskLevel.of(20 * gib, total))
        assertEquals(DiskLevel.CRITICAL, DiskLevel.of(20 * gib - 1, total))
        assertEquals(DiskLevel.CRITICAL, DiskLevel.of(0, total))
        assertEquals(DiskLevel.OK, DiskLevel.of(0, 0))
    }

    @Test
    fun `below one percent free the server may not run`() {
        val total = 1000 * gib
        assertEquals(false, DiskLevel.fatal(10 * gib, total))
        assertEquals(true, DiskLevel.fatal(10 * gib - 1, total))
        assertEquals(true, DiskLevel.fatal(0, total))
        assertEquals(false, DiskLevel.fatal(0, 0))
    }

    @Test
    fun `the worst state wins`() {
        assertEquals(Health.OK, Health.of(false, 12.0, null, DiskLevel.OK))
        assertEquals(Health.FORWARD_COMPATIBLE, Health.of(true, 12.0, 100, DiskLevel.OK))
        assertEquals(Health.HIGH_LOAD, Health.of(true, HIGH_MSPT, 100, DiskLevel.OK))
        assertEquals(Health.DEGRADED, Health.of(true, 45.0, LAG_MILLIS, DiskLevel.OK))
        assertEquals(Health.LOW_DISK, Health.of(false, 45.0, null, DiskLevel.LOW))
        assertEquals(Health.DEGRADED, Health.of(false, null, LAG_MILLIS, DiskLevel.LOW))
        assertEquals(Health.CRITICAL_DISK, Health.of(true, 45.0, LAG_MILLIS, DiskLevel.CRITICAL))
    }

    @Test
    fun `a queue that settled in time is not late`() {
        assertEquals(Health.OK, Health.of(false, null, LAG_MILLIS - 1, DiskLevel.OK))
    }

    @Test
    fun `the write rate is read between the first and last sample`() {
        var now = 0L
        var count = 0L
        val rate = WriteRate({ count }, keep = 3, clock = { now })
        assertNull(rate.perSecond())
        rate.sample()
        now += 5_000
        count += 500
        rate.sample()
        assertEquals(100.0, rate.perSecond()!!, 0.001)
        repeat(3) {
            now += 5_000
            count += 1_000
            rate.sample()
        }
        assertEquals(200.0, rate.perSecond()!!, 0.001)
    }
}
