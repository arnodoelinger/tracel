package com.tracel.storage

import com.tracel.engine.ledger.*
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.engine.ledger.repository.memory.InMemoryLotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.random.Random

/**
 * The store against the in-memory reference, one random ledger history at a time: after every
 * operation both have to agree on every queue, every total, every census and where every lot is.
 */
class LedgerEquivalenceTest {
    private val holders: List<HolderId> = listOf(
        block(0, 64, 0), block(1, 64, 0), block(2, 64, 0), player(1), player(2), player(3),
    )
    private val items = listOf(ItemKey("minecraft:diamond"), ItemKey("minecraft:stick"), ItemKey("minecraft:coal"))
    private val plank = ItemKey("minecraft:oak_planks")

    private class Side(val repo: LotRepository) {
        val ledger = LotLedger(repo)
        var txn = 1L
        fun next() = TxnId(txn++)
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])
    fun `the store and the reference never disagree`(seed: Long, @TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val reference = Side(InMemoryLotRepository())
            val store = Side(stack.repo)
            val random = Random(seed)
            val made = ArrayList<LotId>()

            repeat(400) { step ->
                val op = random.nextInt(100)
                val a = holders.random(random)
                val b = (holders - a).random(random)
                val item = items.random(random)

                suspend fun both(block: suspend (Side) -> Unit) {
                    block(reference)
                    block(store)
                }

                when {
                    op < 25 -> {
                        val q = Quantity(random.nextLong(1, 40))
                        var lot: LotId? = null
                        both { lot = it.ledger.mint(a, item, q, it.next()).id }
                        made += lot!!
                    }

                    op < 50 -> {
                        val have = reference.ledger.totalAt(a, item)?.raw ?: 0L
                        if (have > 0) {
                            val q = Quantity(random.nextLong(1, have + 1))
                            both { it.ledger.move(a, b, item, q, it.next()) }
                        }
                    }

                    op < 58 -> {
                        val have = reference.ledger.totalAt(a, item)?.raw ?: 0L
                        if (have > 1) {
                            val first = random.nextLong(1, have)
                            val owed = listOf(b to first, (holders - a - b).random(random) to (have - first))
                            both { it.ledger.drain(a, item, owed, it.next()) }
                        }
                    }

                    op < 64 -> {
                        val have = reference.ledger.totalAt(a, item)?.raw ?: 0L
                        if (have > 0) {
                            val q = Quantity(random.nextLong(1, have + 1))
                            both { it.ledger.burn(a, item, q, SinkKind.HAZARD, it.next()) }
                        }
                    }

                    op < 80 -> {
                        val placed = made.filter { reference.repo.currentHolderOf(it) != null }
                        if (placed.isNotEmpty()) {
                            val lot = placed.random(random)
                            val from = reference.repo.currentHolderOf(lot)!!
                            val to = holders.random(random)
                            both { it.ledger.moveExact(from, to, lot) }
                        }
                    }

                    op < 86 -> {
                        val placed = made.filter { reference.repo.currentHolderOf(it) != null }
                        if (placed.isNotEmpty()) {
                            val lot = placed.random(random)
                            val from = reference.repo.currentHolderOf(lot)!!
                            both { side ->
                                val portion = side.ledger.withdrawExact(from, lot)
                                side.ledger.deposit(b, listOf(portion))
                            }
                        }
                    }

                    op < 90 -> both { it.repo.relocate(a, b) }

                    // Many lots at once, all or some of an account: whole packs move, partial ones split
                    op < 95 -> {
                        val queue = reference.repo.accountQueue(a, item).map { it.lot.id }
                        if (queue.isNotEmpty()) {
                            val chosen = if (random.nextBoolean()) queue else queue.filter { random.nextInt(3) != 0 }
                            if (chosen.isNotEmpty()) both { side ->
                                val moved = side.ledger.moveExactAll(a, b, chosen)
                                assertEquals(chosen.toSet(), moved.keys, "every lot asked for is accounted for")
                            }
                        }
                    }

                    // A crowd of single lots: moved on, they fill more than one pack
                    op < 97 -> {
                        val n = random.nextInt(200, 700)
                        repeat(n) {
                            var lot: LotId? = null
                            both { lot = it.ledger.mint(a, item, Quantity(1), it.next()).id }
                            made += lot!!
                        }
                    }

                    else -> {
                        val have = reference.ledger.totalAt(a, item)?.raw ?: 0L
                        if (have > 0) {
                            val q = Quantity(random.nextLong(1, have + 1))
                            var lot: LotId? = null
                            both {
                                lot = it.ledger.craft(
                                    listOf(Ingredient(a, item, q)),
                                    Product(b, plank, Quantity(q.raw * 2)),
                                    it.next(),
                                ).output.id
                            }
                            made += lot!!
                        }
                    }
                }
                agree(reference, store, made, random, "seed $seed, step $step, op $op")
            }
        }
    }

    private suspend fun agree(reference: Side, store: Side, made: List<LotId>, random: Random, at: String) {
        for (holder in holders) {
            assertEquals(reference.repo.totalsAt(holder), store.repo.totalsAt(holder), "$at: totals at $holder")
            for (item in items + plank) {
                assertEquals(queue(reference, holder, item), queue(store, holder, item), "$at: queue $holder $item")
            }
        }
        for (item in items + plank) {
            assertEquals(reference.repo.census(item), store.repo.census(item), "$at: census $item")
        }
        val refHolders = reference.repo.currentHoldersOf(made)
        assertEquals(refHolders, store.repo.currentHoldersOf(made), "$at: holders")
        for (lot in if (made.size <= 64) made else List(64) { made.random(random) }) {
            val holder = refHolders[lot] ?: continue
            assertEquals(
                reference.repo.placementOf(holder, lot)?.remaining,
                store.repo.placementOf(holder, lot)?.remaining,
                "$at: placement of $lot",
            )
        }
    }

    private suspend fun queue(side: Side, holder: HolderId, item: ItemKey) =
        side.repo.accountQueue(holder, item).map { it.lot.id to it.remaining }
}
