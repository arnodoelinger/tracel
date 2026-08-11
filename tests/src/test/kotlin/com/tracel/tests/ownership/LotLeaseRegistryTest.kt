package com.tracel.tests.ownership

import com.tracel.engine.ownership.InMemoryLotLeaseRegistry
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The scenario this exists for: admin A runs `/tracel rollback apply` over territory that
 * takes 30 seconds to resolve; admin B starts a second rollback over overlapping territory
 * 20 seconds in. Without a reservation, both would happily mutate the same lots. With one,
 * B's [com.tracel.engine.journal.JournalExecutor.execute] call cannot even be written —
 * there is no [com.tracel.engine.ownership.LotLease] to pass it.
 */
class LotLeaseRegistryTest {
    @Test
    fun `a job can acquire lots nothing else holds`() {
        val registry = InMemoryLotLeaseRegistry()
        val granted = registry.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2)))
        assertInstanceOf(LeaseAcquisition.Granted::class.java, granted)
    }

    @Test
    fun `a second job is denied overlapping lots, and told who holds them`() {
        val registry = InMemoryLotLeaseRegistry()
        val jobA = RollbackJobId(1)
        val jobB = RollbackJobId(2)

        registry.acquire(jobA, setOf(LotId(1), LotId(2), LotId(3)))
        val denied = registry.acquire(jobB, setOf(LotId(3), LotId(4)))

        assertInstanceOf(LeaseAcquisition.Denied::class.java, denied)
        assertEquals(mapOf(LotId(3) to jobA), (denied as LeaseAcquisition.Denied).conflicts)
    }

    @Test
    fun `denial reserves nothing - not even the non-conflicting lots`() {
        val registry = InMemoryLotLeaseRegistry()
        val jobA = RollbackJobId(1)
        val jobB = RollbackJobId(2)

        registry.acquire(jobA, setOf(LotId(1)))
        registry.acquire(jobB, setOf(LotId(1), LotId(99))) // Denied because of lot 1

        // Lot 99 was never actually granted to B, since the whole request was all-or-nothing —
        // a third job must be able to take it
        val jobC = RollbackJobId(3)
        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(jobC, setOf(LotId(99))))
    }

    @Test
    fun `a job re-acquiring its own lots succeeds, not a conflict with itself`() {
        val registry = InMemoryLotLeaseRegistry()
        val job = RollbackJobId(1)
        registry.acquire(job, setOf(LotId(1), LotId(2)))

        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(job, setOf(LotId(1), LotId(2), LotId(3))))
    }

    @Test
    fun `releasing a job frees its lots for someone else`() {
        val registry = InMemoryLotLeaseRegistry()
        val jobA = RollbackJobId(1)
        val jobB = RollbackJobId(2)

        registry.acquire(jobA, setOf(LotId(1)))
        registry.release(jobA)

        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(jobB, setOf(LotId(1))))
    }

    @Test
    fun `disjoint lot sets never conflict`() {
        val registry = InMemoryLotLeaseRegistry()
        registry.acquire(RollbackJobId(1), setOf(LotId(1)))
        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(RollbackJobId(2), setOf(LotId(2))))
    }

    @Test
    fun `extend grows a lease to cover a lot discovered mid-flight`() {
        val registry = InMemoryLotLeaseRegistry()
        val job = RollbackJobId(1)
        val lease = (registry.acquire(job, setOf(LotId(1))) as LeaseAcquisition.Granted).lease

        val extended = registry.extend(lease, setOf(LotId(2)))

        assertInstanceOf(LeaseAcquisition.Granted::class.java, extended)
        assertEquals(setOf(LotId(1), LotId(2)), (extended as LeaseAcquisition.Granted).lease.lotIds)
    }

    @Test
    fun `extending into a lot someone else holds is denied, and the original lease is untouched`() {
        val registry = InMemoryLotLeaseRegistry()
        val jobA = RollbackJobId(1)
        val jobB = RollbackJobId(2)
        val lease = (registry.acquire(jobA, setOf(LotId(1))) as LeaseAcquisition.Granted).lease
        registry.acquire(jobB, setOf(LotId(2)))

        val extended = registry.extend(lease, setOf(LotId(2)))
        assertInstanceOf(LeaseAcquisition.Denied::class.java, extended)

        // A still holds lot 1 — the failed extension did not touch what A already had
        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(jobA, setOf(LotId(1))))
    }

    @Test
    fun `transfer hands every held lot to another job atomically`() {
        val registry = InMemoryLotLeaseRegistry()
        val from = RollbackJobId(1)
        val to = RollbackJobId(2)
        registry.acquire(from, setOf(LotId(1), LotId(2)))

        val transferred = registry.transfer(from, to)

        assertEquals(setOf(LotId(1), LotId(2)), transferred)
        // "to" now holds them, and re-acquiring its own lots is a no-op success, not a conflict
        assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(to, setOf(LotId(1), LotId(2))))

        // "from" holds nothing anymore — trying to re-acquire what it used to hold now conflicts with "to"
        assertInstanceOf(LeaseAcquisition.Denied::class.java, registry.acquire(from, setOf(LotId(1))))
    }

    @Test
    fun `reapAbandoned frees leases older than the given age, and nothing else`() {
        val registry = InMemoryLotLeaseRegistry()
        val stale = RollbackJobId(1)
        val fresh = RollbackJobId(2)
        registry.acquire(stale, setOf(LotId(1)))
        registry.acquire(fresh, setOf(LotId(2)))

        val now = System.currentTimeMillis()
        val reaped = registry.reapAbandoned(nowMillis = now + 10_000, maxAgeMillis = 5_000)

        assertEquals(setOf(stale, fresh), reaped, "both were acquired before the cutoff, so both are reaped")
    }

    @Test
    fun `renewing a lease via acquire resets its abandonment clock`() {
        val registry = InMemoryLotLeaseRegistry()
        val job = RollbackJobId(1)
        registry.acquire(job, setOf(LotId(1)))

        // Simulate the job still being alive and periodically re-confirming its lease
        registry.acquire(job, setOf(LotId(1)))

        val now = System.currentTimeMillis()
        val reaped = registry.reapAbandoned(nowMillis = now, maxAgeMillis = 60_000)
        assertEquals(emptySet<RollbackJobId>(), reaped, "a lease renewed just now is nowhere near abandoned")
    }
}
