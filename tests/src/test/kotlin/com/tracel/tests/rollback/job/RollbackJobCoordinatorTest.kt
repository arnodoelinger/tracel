package com.tracel.tests.rollback.job

import com.tracel.engine.rollback.job.RollbackOutcome
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.rollback.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RollbackJobCoordinatorTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)
    private val steve = player(1)
    private val uniform = RollbackTarget.Uniform(chest)

    @Test
    fun `a rollback of many lots applies as one transaction carrying a flow per step`() = runTest {
        val roots = (1..8).map { world.mint(chest, diamond, 1).id }
        world.move(chest, steve, diamond, 8)

        val outcome = world.rollbackCoordinator().run(RollbackJobId(1), roots, uniform)

        val plan = assertInstanceOf(RollbackOutcome.Applied::class.java, outcome).plan
        assertEquals(8, plan.steps.size, "one step per lot taken back")
        assertEquals(8L, world.count(chest, diamond))
        val written = world.log.all().single()
        assertEquals(plan.steps.size, written.flows.size, "delivered directly: a flow per step, no escrow round-trip")
    }

    @Test
    fun `a plan whose lots another job holds is blocked and applies nothing`() = runTest {
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        world.leases.acquire(RollbackJobId(99), setOf(root.id))

        val outcome = world.rollbackCoordinator().run(RollbackJobId(1), listOf(root.id), uniform)

        val blocked = assertInstanceOf(RollbackOutcome.Blocked::class.java, outcome)
        assertEquals(mapOf(root.id to RollbackJobId(99)), blocked.conflicts)
        assertEquals(10L, world.count(steve, diamond), "the diamonds are where they were")
    }

    @Test
    fun `a non-overlapping rollback runs while another job is still active`() = runTest {
        val otherChest = block(100, 64, 0)
        val bob = player(2)
        val rootA = world.mint(chest, diamond, 5)
        world.move(chest, steve, diamond, 5)
        val rootB = world.mint(otherChest, diamond, 5)
        world.move(otherChest, bob, diamond, 5)
        world.acquireLease(RollbackJobId(1), world.planner().plan(listOf(rootA.id)))

        val outcome = world.rollbackCoordinator().run(
            RollbackJobId(2), listOf(rootB.id), RollbackTarget.Uniform(otherChest),
        )

        assertInstanceOf(RollbackOutcome.Applied::class.java, outcome)
        assertEquals(5L, world.count(otherChest, diamond))
    }

    @Test
    fun `a ledger that moves between planning and reserving is caught`() = runTest {
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        var version = 0L
        var readings = 0
        val witness = suspend {
            if (readings++ == 1) {
                world.move(steve, chest, diamond, 10)
                version++
            }
            version
        }

        val outcome = world.rollbackCoordinator(ledgerVersion = witness).run(RollbackJobId(1), listOf(root.id), uniform)

        assertInstanceOf(RollbackOutcome.Stale::class.java, outcome)
    }

    @Test
    fun `an unmoved ledger applies without replanning`() = runTest {
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        var readings = 0
        val coordinator = world.rollbackCoordinator(ledgerVersion = { readings++; 7L })

        assertInstanceOf(
            RollbackOutcome.Applied::class.java,
            coordinator.run(RollbackJobId(1), listOf(root.id), uniform),
        )
        assertEquals(2, readings, "the witness is read once after planning and once after reserving")
    }
}
