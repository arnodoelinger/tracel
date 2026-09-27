package com.tracel.tests.rollback

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.*
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
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

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))

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
        craft(world, ingot, 9, ironBlock, 1)
        craft(world, ironBlock, 1, ingot, 9)
        if (levels == 3) craft(world, ingot, 9, nugget, 81)

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))

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
        craft(world, log, 16, planks, 64)
        world.ledger.burn(steve, planks, Quantity(32), SinkKind.LAVA, world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))

        golden(
            plan,
            listOf(take(4, 32, steve), RollbackStep.Mint(LotId(3), Quantity(32), SinkKind.LAVA)),
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
        craft(world, log, 16, planks, 64)
        world.ledger.move(steve, alex, planks, Quantity(32), world.nextTxn())

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))

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
        craft(world, log, 1, planks, 4)
        craft(world, planks, 4, table, 1)

        val plan = RollbackPlanner(world.repo, { true }, maxTransformDepth = 1).plan(listOf(root.id))

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
        craft(world, log, 1, planks, 4)
        world.ledger.destroy(steve, LotId(2))

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))

        golden(plan, emptyList(), emptyMap())
        apply(world, plan)
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(steve))
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

    private suspend fun craft(world: LedgerHarness, input: ItemKey, amount: Long, output: ItemKey, produced: Long) {
        world.ledger.craft(
            listOf(Ingredient(steve, input, Quantity(amount))),
            Product(steve, output, Quantity(produced)),
            world.nextTxn()
        )
    }

    private suspend fun apply(world: LedgerHarness, plan: RollbackPlan) {
        val job = RollbackJobId(1)
        JournalExecutor(
            RollbackExecutor(world.ledger, world.log, world::nextSeq),
            InMemoryJournal(),
            world.leases,
            world::nextTxn
        )
            .execute(world.acquireLease(job, plan), plan, RollbackTarget.Uniform(chest))
        assertEquals(emptyMap<ItemKey, Quantity>(), world.ledger.totalsAt(HolderId.Escrow(job)))
    }
}