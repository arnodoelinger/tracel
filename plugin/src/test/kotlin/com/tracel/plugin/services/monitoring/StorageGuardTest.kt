package com.tracel.plugin.services.monitoring

import com.tracel.engine.store.WritesStop
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StorageGuardTest {
    private val now = 1_000_000L

    @Test
    fun `a healthy store with nothing lost keeps the server up`() {
        assertNull(StorageGuard.verdict(null, 0, now))
    }

    @Test
    fun `a stop worth waiting for gets its grace`() {
        val stop = WritesStop(now - WRITES_STOPPED_GRACE_MILLIS + 1, fatal = false, reason = "No space left on device")
        assertNull(StorageGuard.verdict(stop, 0, now))
        assertNotNull(StorageGuard.verdict(stop, 0, now + 1))
    }

    @Test
    fun `a hopeless stop goes down at once`() {
        assertNotNull(StorageGuard.verdict(WritesStop(now, fatal = true, reason = "sync failed"), 0, now))
    }

    @Test
    fun `one lost event is enough`() {
        assertNotNull(StorageGuard.verdict(null, 1, now))
    }
}
