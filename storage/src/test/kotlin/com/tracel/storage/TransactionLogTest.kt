package com.tracel.storage

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.WorldId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class TransactionLogTest {
    private val world = WorldId(UUID(0L, 1L))

    private fun move(seq: Long, from: HolderId, to: HolderId, at: Long, cause: CauseKind = CauseKind.MACHINE) =
        Transaction(
            TxnId(seq),
            Seq(seq),
            at,
            cause,
            from,
            listOf(Flow(diamond, Quantity(1), from, to, FlowKind.MOVE)),
        )

    @Test
    fun `the lots a transaction moved come back in flow order`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val txn = Transaction(
                TxnId(4),
                Seq(4),
                1L,
                CauseKind.PLAYER_ACTION,
                player(1),
                listOf(
                    Flow(diamond, Quantity(3), block(1, 2, 3), player(1), FlowKind.MOVE),
                    Flow(diamond, Quantity(2), block(4, 5, 6), player(1), FlowKind.MOVE),
                ),
                listOf(
                    FlowLot(0, LotId(50), Quantity(1)),
                    FlowLot(0, LotId(51), Quantity(2)),
                    FlowLot(1, LotId(52), Quantity(2)),
                ),
            )
            stack.log.append(txn)

            assertEquals(txn.lots, stack.log.lotsAt(Seq(4)))
        }
    }

    @Test
    fun `a transaction with no lot linkage reads back as none, not as a failure`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.log.append(move(1, player(1), block(1, 2, 3), 1L))
            assertEquals(emptyList<FlowLot>(), stack.log.lotsAt(Seq(1)))
        }
    }

    @Test
    fun `one transaction's lots never bleed into the next`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (seq in 1L..3L) {
                stack.log.append(
                    Transaction(
                        TxnId(seq),
                        Seq(seq),
                        seq,
                        CauseKind.MACHINE,
                        player(1),
                        listOf(Flow(diamond, Quantity(1), player(1), block(1, 2, 3), FlowKind.MOVE)),
                        listOf(FlowLot(0, LotId(seq * 10), Quantity(1))),
                    )
                )
            }

            assertEquals(listOf(FlowLot(0, LotId(20), Quantity(1))), stack.log.lotsAt(Seq(2)))
        }
    }

    @Test
    fun `a transaction round-trips every field`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val txn = Transaction(
                TxnId(7),
                Seq(7),
                1_700_000_000_000L,
                CauseKind.EXPLOSION,
                player(9),
                listOf(
                    Flow(diamond, Quantity(3), block(1, 2, 3), player(1), FlowKind.MOVE),
                    Flow(
                        ItemKey("minecraft:diamond_sword", ContentHash("deadbeef")),
                        Quantity(1),
                        HolderId.Source(com.tracel.model.holder.SourceKind.CRAFT),
                        player(1),
                        FlowKind.TRANSFORM_OUT,
                    ),
                ),
            )
            stack.log.append(txn)
            assertEquals(txn, stack.log.find(TxnId(7)))
        }
    }

    @Test
    fun `appending the same id twice is refused`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.log.append(move(1, block(0, 64, 0), player(1), 100))
            val again = runCatching { stack.log.append(move(1, block(0, 64, 0), player(1), 100)) }.exceptionOrNull()
            assertTrue(again is IllegalStateException, "the log is append-only, got $again")
        }
    }

    @Test
    fun `an unfiltered query comes back newest first`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (i in 1L..20L) stack.log.append(move(i, block(0, 64, 0), player(1), 1000 + i))
            val result = stack.log.query(LookupFilter(limit = 5))
            assertEquals(listOf(20L, 19L, 18L, 17L, 16L), result.map { it.seq.raw })
        }
    }

    @Test
    fun `a holder filter finds only transactions touching that holder`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            val alex = player(2)
            for (i in 1L..10L) stack.log.append(move(i, chest, if (i % 2 == 0L) steve else alex, 1000 + i))

            val steveOnly = stack.log.query(LookupFilter(holders = setOf(steve)))
            assertEquals(listOf(10L, 8L, 6L, 4L, 2L), steveOnly.map { it.seq.raw })
        }
    }

    @Test
    fun `an excluded holder removes its transactions`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            val alex = player(2)
            for (i in 1L..10L) stack.log.append(move(i, chest, if (i % 2 == 0L) steve else alex, 1000 + i))

            val withoutSteve = stack.log.query(LookupFilter(excludedHolders = setOf(steve)))
            assertEquals(listOf(9L, 7L, 5L, 3L, 1L), withoutSteve.map { it.seq.raw })
        }
    }

    @Test
    fun `a time-bounded region query skips older history in the same chunks`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            for (i in 1L..500L) stack.log.append(move(i, chest, steve, at = i))
            stack.log.append(move(501, chest, steve, at = 4_000_000))

            val here = LookupRegion(world, 0, 0, 0, 0)
            assertEquals(
                listOf(501L),
                stack.log.query(LookupFilter(region = here, since = 3_600_000, limit = Int.MAX_VALUE))
                    .map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a time range stops at its own boundaries`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (i in 1L..20L) stack.log.append(move(i, block(0, 64, 0), player(1), i * 100))
            val window = stack.log.query(LookupFilter(since = 500, until = 900))
            assertEquals(listOf(9L, 8L, 7L, 6L, 5L), window.map { it.seq.raw })
        }
    }

    @Test
    fun `a cause filter narrows to that cause`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (i in 1L..10L) {
                val cause = if (i % 3 == 0L) CauseKind.EXPLOSION else CauseKind.MACHINE
                stack.log.append(move(i, block(0, 64, 0), player(1), 1000 + i, cause))
            }
            val explosions = stack.log.query(LookupFilter(causes = setOf(CauseKind.EXPLOSION)))
            assertEquals(listOf(9L, 6L, 3L), explosions.map { it.seq.raw })
        }
    }

    @Test
    fun `a material filter matches decorated variants of the same material`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val plain = ItemKey("minecraft:diamond_sword")
            val enchanted = ItemKey("minecraft:diamond_sword", ContentHash("cafe"))
            val steve = player(1)
            stack.log.append(
                Transaction(
                    TxnId(1),
                    Seq(1),
                    100,
                    CauseKind.MACHINE,
                    null,
                    listOf(Flow(plain, Quantity(1), block(0, 0, 0), steve, FlowKind.MOVE))
                ),
            )
            stack.log.append(
                Transaction(
                    TxnId(2),
                    Seq(2),
                    200,
                    CauseKind.MACHINE,
                    null,
                    listOf(Flow(enchanted, Quantity(1), block(0, 0, 0), steve, FlowKind.MOVE))
                ),
            )
            stack.log.append(
                Transaction(
                    TxnId(3),
                    Seq(3),
                    300,
                    CauseKind.MACHINE,
                    null,
                    listOf(Flow(diamond, Quantity(1), block(0, 0, 0), steve, FlowKind.MOVE))
                ),
            )

            val swords = stack.log.query(LookupFilter(material = "minecraft:diamond_sword"))
            assertEquals(listOf(2L, 1L), swords.map { it.seq.raw })
        }
    }

    @Test
    fun `offset pages through the result`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (i in 1L..20L) stack.log.append(move(i, block(0, 64, 0), player(1), 1000 + i))
            assertEquals(listOf(15L, 14L), stack.log.query(LookupFilter(limit = 2, offset = 5)).map { it.seq.raw })
        }
    }

    @Test
    fun `a batched region query does not fetch years of a chunk to keep an hour`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            for (seq in 1L..400L) stack.log.append(move(seq, block(3, 64, 5), steve, seq))
            stack.log.append(move(401, block(3, 64, 5), steve, 10_000))
            stack.log.append(move(402, block(1608, 64, 1608), steve, 10_100))

            assertEquals(
                listOf(401L),
                stack.log.query(
                    LookupFilter(
                        region = LookupRegion(world, 0, 0, 0, 0),
                        since = 9_000,
                        limit = Int.MAX_VALUE,
                    ),
                ).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a player filter still honours the radius`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.log.append(move(1, block(3, 64, 5), steve, 100))
            stack.log.append(move(2, block(1608, 64, 1608), steve, 200))
            stack.log.append(move(3, block(9, 64, 9), player(2), 300))

            val nearby = LookupRegion(
                world, 0, 0, 0, 0,
                minX = 0, maxX = 15, minY = 0, maxY = 128, minZ = 0, maxZ = 15,
            )
            assertEquals(
                listOf(1L),
                stack.log.query(LookupFilter(holders = setOf(steve), region = nearby)).map { it.seq.raw },
                "u:steve r:10 must not reclaim the chest 1600 blocks away",
            )
        }
    }

    @Test
    fun `a chunk region only returns transactions inside it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            // Chunk (0,0) is blocks 0..15; chunk (100,100) is a long way from it
            stack.log.append(move(1, block(3, 64, 5), player(1), 100))
            stack.log.append(move(2, block(1608, 64, 1608), player(1), 200))
            stack.log.append(move(3, block(9, 64, 9), player(1), 300))

            val here = stack.log.query(LookupFilter(region = LookupRegion(world, 0, 0, 0, 0)))
            assertEquals(listOf(3L, 1L), here.map { it.seq.raw })

            val there = stack.log.query(LookupFilter(region = LookupRegion(world, 100, 100, 100, 100)))
            assertEquals(listOf(2L), there.map { it.seq.raw })
        }
    }

    @Test
    fun `a world filter and a region both apply, and a region does not swallow the world`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val other = WorldId(UUID(0L, 2L))
                fun elsewhere(x: Int, y: Int, z: Int) = HolderId.Block(other, x, y, z)
                stack.log.append(move(1, block(3, 64, 5), player(1), 100))
                stack.log.append(move(2, elsewhere(3, 64, 5), player(1), 200))

                val here = stack.log.query(LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), world = world))
                assertEquals(listOf(1L), here.map { it.seq.raw })

                val impossible = stack.log.query(LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), world = other))
                assertTrue(impossible.isEmpty())
            }
        }

    @Test
    fun `a region query still honours limit, unlike filtering a global page afterwards`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            // One transaction in the chunk we care about, then a thousand somewhere else. A
            // post-filter over the newest hundred would return nothing at all.
            stack.log.append(move(1, block(3, 64, 5), player(1), 100))
            for (i in 2L..1001L) stack.log.append(move(i, block(5000, 64, 5000), player(1), 100 + i))

            val here = stack.log.query(LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), limit = 100))
            assertEquals(listOf(1L), here.map { it.seq.raw })
        }
    }

    @Test
    fun `a filter no transaction can match returns nothing rather than everything`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.log.append(move(1, block(0, 64, 0), player(1), 100))
            assertTrue(stack.log.query(LookupFilter(holders = setOf(player(99)))).isEmpty())
            assertTrue(stack.log.query(LookupFilter(material = "minecraft:netherite_hoe")).isEmpty())
            assertNull(stack.log.find(TxnId(404)))
        }
    }

    @Test
    fun `the log survives a reopen`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (i in 1L..50L) stack.log.append(move(i, block(0, 64, 0), player(1), 1000 + i))
        }
        Stack(dir).use { stack ->
            assertEquals(50, stack.log.query(LookupFilter(limit = 1000)).size)
            assertEquals(move(7, block(0, 64, 0), player(1), 1007), stack.log.find(TxnId(7)))
        }
    }

    @Test
    fun `bookkeeping is findable by id but invisible to lookup`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)

            stack.log.append(move(1, chest, steve, at = 100))
            stack.log.append(move(2, steve, chest, at = 200, cause = CauseKind.ROLLBACK))
            stack.log.append(move(3, chest, steve, at = 300, cause = CauseKind.INVOLUTION))

            assertEquals(move(2, steve, chest, at = 200, cause = CauseKind.ROLLBACK), stack.log.find(TxnId(2)))
            assertEquals(move(3, chest, steve, at = 300, cause = CauseKind.INVOLUTION), stack.log.find(TxnId(3)))

            val spatialKeys = stack.storage.read {
                var n = 0
                scan(byteArrayOf(com.tracel.storage.codec.Keys.SPATIAL)).use { cursor -> while (cursor.next()) n++ }
                n
            }
            assertEquals(1, spatialKeys, "a restore must not add spatial keys for the next query to walk")

            val region = LookupRegion(world, 0, 0, 0, 0)
            assertEquals(
                listOf(Seq(1)),
                stack.log.query(LookupFilter(limit = Int.MAX_VALUE)).map { it.seq },
                "lookup must not treat a restore as ordinary history",
            )
            assertEquals(
                listOf(Seq(1)),
                stack.log.query(LookupFilter(limit = Int.MAX_VALUE, region = region)).map { it.seq },
            )
            assertEquals(
                listOf(Seq(1)),
                stack.log.query(LookupFilter(limit = Int.MAX_VALUE, holders = setOf(steve))).map { it.seq },
            )
        }
    }

    @Test
    fun `lot linkage for a dense batch of transactions is one walk, not one scan per transaction`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val seqs = ArrayList<Seq>()
                for (seq in 1L..100L) {
                    stack.log.append(
                        Transaction(
                            TxnId(seq), Seq(seq), seq, CauseKind.MACHINE, player(1),
                            listOf(Flow(diamond, Quantity(1), player(1), block(1, 2, 3), FlowKind.MOVE)),
                            listOf(FlowLot(0, LotId(seq * 10), Quantity(1))),
                        )
                    )
                    seqs += Seq(seq)
                }

                val loaded = stack.log.lotsAtAll(seqs)

                assertEquals(100, loaded.size)
                for (seq in 1L..100L) {
                    assertEquals(listOf(FlowLot(0, LotId(seq * 10), Quantity(1))), loaded[Seq(seq)])
                }
            }
        }

    @Test
    fun `lot linkage still skips transactions with none in the middle of a dense batch`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val seqs = ArrayList<Seq>()
            for (seq in 1L..80L) {
                val lots = if (seq % 3 == 0L) emptyList() else listOf(FlowLot(0, LotId(seq * 10), Quantity(1)))
                stack.log.append(
                    Transaction(
                        TxnId(seq), Seq(seq), seq, CauseKind.MACHINE, player(1),
                        listOf(Flow(diamond, Quantity(1), player(1), block(1, 2, 3), FlowKind.MOVE)),
                        lots,
                    )
                )
                seqs += Seq(seq)
            }

            val loaded = stack.log.lotsAtAll(seqs)

            assertEquals(80 - 26, loaded.size, "26 multiples of 3 between 1 and 80 carried no lots")
            assertNull(loaded[Seq(3)])
            assertEquals(listOf(FlowLot(0, LotId(40), Quantity(1))), loaded[Seq(4)])
        }
    }

    @Test
    fun `lot linkage for a scattered batch seeks over the gaps and still finds every one`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val wanted = ArrayList<Seq>()
                for (seq in 1L..20_000L) {
                    stack.log.append(
                        Transaction(
                            TxnId(seq), Seq(seq), seq, CauseKind.MACHINE, player(1),
                            listOf(Flow(diamond, Quantity(1), player(1), block(1, 2, 3), FlowKind.MOVE)),
                            listOf(FlowLot(0, LotId(seq * 10), Quantity(1))),
                        )
                    )
                    if (seq % 100L == 0L) wanted += Seq(seq)
                }

                val loaded = stack.log.lotsAtAll(wanted)

                assertEquals(wanted.size, loaded.size)
                for (seq in wanted) {
                    assertEquals(listOf(FlowLot(0, LotId(seq.raw * 10), Quantity(1))), loaded[seq], "lost $seq")
                }
                assertEquals(
                    listOf(FlowLot(0, LotId(190_000), Quantity(1))),
                    stack.log.lotsAtAll(listOf(Seq(19_000)))[Seq(19_000)],
                )
            }
        }
}
