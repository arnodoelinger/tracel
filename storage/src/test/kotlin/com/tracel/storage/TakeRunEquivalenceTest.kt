package com.tracel.storage

import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ledger.PlacedRuns
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.InvolutionExecutor
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.job.RollbackOutcome
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.random.Random

/**
 * A rollback taking runs whole against the same rollback walked lot by lot: same history, two stores,
 * and after the job both have to hold exactly the same things in exactly the same order.
 */
class TakeRunEquivalenceTest {
    private val holders: List<HolderId> = listOf(
        block(0, 64, 0), block(1, 64, 0), block(2, 64, 0), player(1), player(2), player(3),
    )
    private val items = listOf(ItemKey("minecraft:diamond"), ItemKey("minecraft:stick"))
    private val plank = ItemKey("minecraft:oak_planks")

    /** The same store with the shortcut switched off: every root walks. */
    private class Walked(private val inner: LotRepository) : LotRepository by inner {
        override suspend fun placedRuns(roots: Collection<LotId>): PlacedRuns = PlacedRuns(emptyList(), roots.distinct())
    }

    private suspend fun history(stack: Stack, seed: Long): List<LotId> {
        val random = Random(seed)
        val made = ArrayList<LotId>()
        repeat(160) {
            val a = holders.random(random)
            val b = (holders - a).random(random)
            val item = items.random(random)
            val have = stack.ledger.totalAt(a, item)?.raw ?: 0L
            when (random.nextInt(100)) {
                in 0 until 30 -> repeat(random.nextInt(1, 30)) {
                    made += stack.ledger.mint(a, item, Quantity(random.nextLong(1, 5)), stack.counters.nextTxnId()).id
                }
                in 30 until 70 -> if (have > 0) {
                    stack.ledger.move(a, b, item, Quantity(random.nextLong(1, have + 1)), stack.counters.nextTxnId())
                }
                in 70 until 80 -> if (have > 0) {
                    stack.ledger.burn(a, item, Quantity(random.nextLong(1, have + 1)), SinkKind.LAVA, stack.counters.nextTxnId())
                }
                in 80 until 88 -> if (have > 0) {
                    made += stack.ledger.craft(
                        listOf(Ingredient(a, item, Quantity(random.nextLong(1, have + 1)))),
                        Product(b, plank, Quantity(2)),
                        stack.counters.nextTxnId(),
                    ).output.id
                }
                else -> stack.repo.relocate(a, b)
            }
        }
        return made
    }

    private fun coordinator(stack: Stack, repo: LotRepository) = RollbackJobCoordinator(
        repo,
        { true },
        stack.leases,
        JournalExecutor(
            RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
            stack.journal,
            stack.leases,
            stack.counters::nextTxnId,
        ),
        stack.jobs,
        ledgerVersion = stack.counters::peekTxnId,
    )

    private fun undoer(stack: Stack) = InvolutionJobCoordinator(
        stack.jobs,
        stack.repo,
        stack.leases,
        InvolutionExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
        stack.involutionJournal,
        stack.counters::nextTxnId,
    )

    private fun expanded(plan: RollbackPlan, target: RollbackTarget): Set<String> =
        plan.steps.flatMap { step ->
            when (step) {
                is RollbackStep.TakeRun -> step.takes()
                else -> listOf(step)
            }
        }.map { step ->
            if (step is RollbackStep.Take) "$step -> ${target.destinationFor(plan, step.lotId)}" else step.toString()
        }.toSet()

    private suspend fun state(stack: Stack): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (holder in holders) for (item in items + plank) {
            out["queue $holder $item"] = stack.repo.accountQueue(holder, item).map { it.lot.id to it.remaining }
        }
        for (item in items + plank) out["census $item"] = stack.repo.census(item)
        return out
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
    fun `taking runs whole ends where walking every lot ends`(seed: Long, @TempDir dir: Path) = runTest {
        Stack(dir.resolve("runs")).use { runs ->
            Stack(dir.resolve("walked")).use { walked ->
                val made = history(runs, seed)
                assertEquals(made, history(walked, seed), "fresh stores assign the same ids")

                val random = Random(seed * 31)
                val roots = made.filter { random.nextInt(3) != 0 }
                val target: RollbackTarget = if (random.nextBoolean()) {
                    RollbackTarget.Uniform(holders.random(random))
                } else {
                    RollbackTarget.PerRoot(roots.associateWith { holders.random(random) })
                }

                val fast = coordinator(runs, runs.repo).run(RollbackJobId(1), roots, target)
                val slow = coordinator(walked, Walked(walked.repo)).run(RollbackJobId(1), roots, target)
                assertTrue(fast is RollbackOutcome.Applied, "seed $seed: $fast")
                assertTrue(slow is RollbackOutcome.Applied, "seed $seed: $slow")
                val fastPlan = (fast as RollbackOutcome.Applied).plan
                val slowPlan = (slow as RollbackOutcome.Applied).plan

                assertTrue(fastPlan.steps.any { it is RollbackStep.TakeRun }, "seed $seed: the history has to leave something to take whole")
                assertEquals(expanded(slowPlan, target), expanded(fastPlan, target), "seed $seed: same steps")
                assertEquals(state(walked), state(runs), "seed $seed: same ledger afterwards")

                // Undone from what is on disk: the runs go through the codec and come back as takes
                val undoFast = undoer(runs).undo(RollbackJobId(1))
                val undoSlow = undoer(walked).undo(RollbackJobId(1))
                assertTrue(undoFast is InvolutionOutcome.Undone, "seed $seed: $undoFast")
                assertEquals(
                    (undoSlow as InvolutionOutcome.Undone).steps.toSet(),
                    (undoFast as InvolutionOutcome.Undone).steps.toSet(),
                    "seed $seed: same undo",
                )
                assertEquals(state(walked), state(runs), "seed $seed: same ledger after the undo")
            }
        }
    }
}
