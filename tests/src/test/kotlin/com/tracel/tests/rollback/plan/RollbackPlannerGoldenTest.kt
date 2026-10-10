package com.tracel.tests.rollback.plan

import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.physicalDeltas
import com.tracel.engine.rollback.plan.step.LotContribution
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.engine.rollback.plan.step.UnmadeOutput
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RollbackPlannerGoldenTest {
    private val steve = player(1)
    private val alex = player(2)
    private val chest = block(0, 64, 0)
    private val log = ItemKey("minecraft:oak_log")
    private val planks = ItemKey("minecraft:oak_planks")
    private val table = ItemKey("minecraft:crafting_table")

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `simple take stays a take even when its player is offline`(online: Boolean) = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())

        val plan = RollbackPlanner(world.repo, { online }).plan(listOf(root.id))

        golden(plan, listOf(take(1, 10, steve)), mapOf(LotId(1) to LotId(1)))
        assertEquals(
            mapOf(steve to mapOf(diamond to -10L), chest to mapOf(diamond to 10L)),
            physicalDeltas(plan, chest, world.ledger),
        )
        apply(world, plan)
        assertEquals(mapOf(diamond to Quantity(10)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(10L, world.ledger.census(diamond))
    }

    @Test
    fun `split lot takes both exact pieces in the original order`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(steve, alex, diamond, Quantity(4), world.nextTxn())

        val plan = world.planner().plan(listOf(root.id))

        golden(
            plan,
            listOf(take(3, 6, steve), take(2, 4, alex)),
            mapOf(LotId(3) to root.id, LotId(2) to root.id),
        )
        apply(world, plan)
        assertEquals(mapOf(diamond to Quantity(10)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(alex))
        assertEquals(10L, world.ledger.census(diamond))
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `two and three level crafts unmake from the output back to the root`(levels: Int) = runTest {
        val world = LedgerHarness()
        val ingot = ItemKey("minecraft:iron_ingot")
        val ironBlock = ItemKey("minecraft:iron_block")
        val nugget = ItemKey("minecraft:iron_nugget")
        val root = world.ledger.mint(steve, ingot, Quantity(9), world.nextTxn())
        world.craft(steve, ingot, 9, ironBlock, 1)
        world.craft(steve, ironBlock, 1, ingot, 9)
        if (levels == 3) world.craft(steve, ingot, 9, nugget, 81)

        val plan = world.planner().plan(listOf(root.id))

        val expected = buildList {
            if (levels == 3) add(unmake(4, 3, 9, 4))
            add(unmake(3, 2, 1, 3))
            add(unmake(2, 1, 9, 2))
            add(take(1, 9, steve))
        }
        val roots = buildMap {
            put(LotId(1), LotId(1))
            put(LotId(2), LotId(1))
            put(LotId(3), LotId(3))
            if (levels == 3) put(LotId(4), LotId(4))
        }
        golden(plan, expected, roots)
        apply(world, plan)
        assertEquals(mapOf(ingot to Quantity(9)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(listOf(9L, 0L, 0L), listOf(ingot, ironBlock, nugget).map { world.ledger.census(it) })
    }

    @Test
    fun `partly lost craft returns planks and compensates the lost half without unmaking`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, log, Quantity(16), world.nextTxn())
        world.craft(steve, log, 16, planks, 64)
        world.ledger.burn(steve, planks, Quantity(32), SinkKind.HAZARD, world.nextTxn())

        val plan = world.planner().plan(listOf(root.id))

        golden(
            plan,
            listOf(take(4, 32, steve), RollbackStep.Mint(LotId(3), Quantity(32), SinkKind.HAZARD)),
            mapOf(LotId(4) to root.id, LotId(3) to root.id),
        )
        apply(world, plan)
        assertEquals(mapOf(planks to Quantity(64)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(64L, world.ledger.census(planks))
        assertEquals(0L, world.ledger.census(log))
    }

    @Test
    fun `scattered craft returns its pieces instead of the ingredient`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, log, Quantity(16), world.nextTxn())
        world.craft(steve, log, 16, planks, 64)
        world.ledger.move(steve, alex, planks, Quantity(32), world.nextTxn())

        val plan = world.planner().plan(listOf(root.id))

        golden(plan, listOf(take(4, 32, steve), take(3, 32, alex)), mapOf(LotId(4) to root.id, LotId(3) to root.id))
        apply(world, plan)
        assertEquals(mapOf(planks to Quantity(64)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(alex))
        assertEquals(64L, world.ledger.census(planks))
        assertEquals(0L, world.ledger.census(log))
    }

    @Test
    fun `depth limit keeps the existing gap compensation without unmaking the craft`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, log, Quantity(1), world.nextTxn())
        world.craft(steve, log, 1, planks, 4)
        world.craft(steve, planks, 4, table, 1)

        val plan = world.planner(maxTransformDepth = 1).plan(listOf(root.id))

        golden(
            plan,
            listOf(RollbackStep.Mint(root.id, Quantity(1), SinkKind.UNTRACKED_GAP)),
            mapOf(root.id to root.id),
        )
        apply(world, plan)
        assertEquals(mapOf(log to Quantity(1)), world.ledger.totalsAt(chest))
        assertEquals(mapOf(table to Quantity(1)), world.ledger.totalsAt(steve))
    }

    @Test
    fun `a transform whose output was retired has no steps`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, log, Quantity(1), world.nextTxn())
        world.craft(steve, log, 1, planks, 4)
        world.ledger.destroy(steve, LotId(2))

        val plan = world.planner().plan(listOf(root.id))

        golden(plan, emptyList(), emptyMap())
        apply(world, plan)
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
    }

    @Test
    fun `a rejected whole output does not unmake a craft outside the share`() = runTest {
        val world = LedgerHarness()
        val coal = ItemKey("minecraft:coal")
        val torch = ItemKey("minecraft:torch")
        val lantern = ItemKey("minecraft:lantern")
        val root = world.ledger.mint(steve, log, Quantity(2), world.nextTxn())
        world.ledger.mint(steve, coal, Quantity(6), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, log, Quantity(2)), Ingredient(steve, coal, Quantity(6))),
            Product(steve, torch, Quantity(8)),
            world.nextTxn(),
        )
        world.ledger.move(steve, alex, torch, Quantity(2), world.nextTxn())
        world.craft(steve, torch, 6, lantern, 1)

        val plan = world.planner().plan(listOf(root.id))

        golden(plan, listOf(take(4, 2, alex)), mapOf(LotId(4) to root.id))
    }

    @Test
    fun `a whole output with a crafted piece is still unmade as one`() = runTest {
        val world = LedgerHarness()
        val root = world.ledger.mint(steve, log, Quantity(4), world.nextTxn())
        world.craft(steve, log, 4, planks, 16)
        world.craft(steve, planks, 8, table, 1)

        val plan = world.planner().plan(listOf(root.id))

        assertEquals(2, plan.steps.count { it is RollbackStep.Unmake })
        assertEquals(listOf(take(1, 4, steve)), plan.steps.filterIsInstance<RollbackStep.Take>())
        apply(world, plan)
        assertEquals(mapOf(log to Quantity(4)), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
        assertEquals(0L, world.ledger.census(planks))
        assertEquals(0L, world.ledger.census(table))
    }

    private fun golden(plan: RollbackPlan, steps: List<RollbackStep>, rootOf: Map<LotId, LotId>) {
        assertEquals(steps, plan.steps)
        assertEquals(rootOf, plan.rootOf)
        assertEquals(emptySet<LotId>(), plan.settled)
    }

    private fun take(id: Long, quantity: Long, holder: HolderId) =
        RollbackStep.Take(LotId(id), Quantity(quantity), holder)

    private fun unmake(output: Long, input: Long, quantity: Long, txn: Long) = RollbackStep.Unmake(
        listOf(UnmadeOutput(LotId(output), steve)),
        listOf(LotContribution(LotId(input), Quantity(quantity))),
        TxnId(txn),
        steve,
    )

    private suspend fun apply(world: LedgerHarness, plan: RollbackPlan) {
        val job = world.nextJob()
        world.journalExecutor().execute(world.acquireLease(job, plan), plan, RollbackTarget.Uniform(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(HolderId.Escrow(job)))
    }
}
