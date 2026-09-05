package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.*
import com.tracel.storage.ports.ops.purgeAll
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class WorldLogTest {
    private val world = WorldId(UUID(0L, 1L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))

    private fun at(x: Int, y: Int, z: Int) = BlockPos(world, x, y, z)

    private fun broke(
        seq: Long,
        pos: BlockPos,
        before: BlockShape = stone,
        by: HolderId? = player(1),
        epochMillis: Long = seq,
    ) = WorldChange(
        Seq(seq),
        ActionKind.BLOCK_BREAK,
        CauseKind.PLAYER_ACTION,
        by,
        epochMillis,
        pos,
        ChangeSubject.Block(before, BlockShape.AIR),
    )

    @Test
    fun `a block change round-trips every field`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val sign = BlockShape(
                BlockDataKey("minecraft:oak_sign[rotation=4,waterlogged=false]"),
                BlockExtras.Opaque(byteArrayOf(1, 2, 3, 4, 5)),
            )
            val change = WorldChange(
                Seq(1),
                ActionKind.BLOCK_BREAK,
                CauseKind.EXPLOSION,
                player(9),
                1_700_000_000_000L,
                at(-30_000_000, -64, 30_000_000),
                ChangeSubject.Block(sign, BlockShape.AIR),
            )
            stack.worldLog.append(change)

            assertEquals(listOf(change), stack.worldLog.at(change.at))
        }
    }

    @Test
    fun `a block's history reads newest first and stops at its own coordinate`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val here = at(10, 70, -3)
            for (seq in 1L..3L) stack.worldLog.append(broke(seq, here))
            stack.worldLog.append(broke(4, at(11, 70, -3)))
            stack.worldLog.append(broke(5, at(10, 71, -3)))

            assertEquals(listOf(3L, 2L, 1L), stack.worldLog.at(here).map { it.seq.raw })
            assertEquals(listOf(3L, 2L), stack.worldLog.at(here, limit = 2).map { it.seq.raw })
        }
    }

    @Test
    fun `a region query finds changes by chunk and a time window narrows them`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(5, 70, 5), epochMillis = 100))
            stack.worldLog.append(broke(2, at(300, 70, 5), epochMillis = 200))
            stack.worldLog.append(broke(3, at(6, 70, 6), epochMillis = 300))

            val nearby = LookupRegion(world, minChunkX = 0, maxChunkX = 0, minChunkZ = 0, maxChunkZ = 0)
            assertEquals(
                listOf(3L, 1L),
                stack.worldLog.query(LookupFilter(region = nearby)).map { it.seq.raw },
                "the block 300 away is in another chunk",
            )
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(region = nearby, until = 150)).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a batched region query does not fetch years of a chunk to keep an hour`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val nearby = LookupRegion(world, 0, 0, 0, 0)
            for (seq in 1L..400L) stack.worldLog.append(broke(seq, at(5, 70, 5), epochMillis = seq))
            stack.worldLog.append(broke(401, at(5, 70, 5), epochMillis = 10_000))
            stack.worldLog.append(broke(402, at(6, 70, 6), epochMillis = 10_100))
            stack.worldLog.append(broke(403, at(300, 70, 5), epochMillis = 10_200))

            assertEquals(
                listOf(402L, 401L),
                stack.worldLog.query(
                    LookupFilter(region = nearby, since = 9_000, limit = Int.MAX_VALUE),
                ).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a player filter still honours the radius`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.worldLog.append(broke(1, at(5, 70, 5), by = steve))
            stack.worldLog.append(broke(2, at(300, 70, 5), by = steve))
            stack.worldLog.append(broke(3, at(6, 70, 6), by = player(2)))

            val nearby = LookupRegion(
                world, minChunkX = 0, maxChunkX = 0, minChunkZ = 0, maxChunkZ = 0,
                minX = 0, maxX = 10, minY = 60, maxY = 80, minZ = 0, maxZ = 10,
            )
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(holders = setOf(steve), region = nearby)).map { it.seq.raw },
                "u:steve r:10 must not roll back the block 300 away, or the neighbour's block",
            )
        }
    }

    @Test
    fun `queryTogether matches two separate queries from one walk`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.worldLog.append(broke(1, at(1, 70, 1), by = steve, epochMillis = 100))
            stack.log.append(
                Transaction(
                    TxnId(2), Seq(2), 200, CauseKind.PLAYER_ACTION, steve,
                    listOf(Flow(diamond, Quantity(1), HolderId.Block(world, 1, 70, 1), steve, FlowKind.MOVE)),
                )
            )
            stack.worldLog.append(broke(3, at(1, 70, 1), by = steve, epochMillis = 300))

            val filter = LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), limit = Int.MAX_VALUE)
            val (worlds, txns) = stack.worldLog.queryTogether(stack.log, filter)
            assertEquals(stack.worldLog.query(filter).map { it.seq.raw }, worlds.map { it.seq.raw })
            assertEquals(stack.log.query(filter).map { it.seq.raw }, txns.map { it.seq.raw })
        }
    }

    @Test
    fun `structureEnds keeps newest and oldest at each block, not every grass tick`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val pos = at(5, 70, 5)
            for (i in 1L..500L) stack.worldLog.append(broke(i, pos, epochMillis = i))
            val other = at(6, 70, 5)
            stack.worldLog.append(broke(501, other, epochMillis = 501))

            val filter = LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), limit = Int.MAX_VALUE)
            val (all, _) = stack.worldLog.queryTogether(stack.log, filter)
            val (ends, _) = stack.worldLog.queryTogether(stack.log, filter, structureEnds = true)
            assertEquals(501, all.size)
            assertEquals(3, ends.size, "two ends at the busy block plus the one change next to it")
            assertEquals(setOf(500L, 1L, 501L), ends.map { it.seq.raw }.toSet())
        }
    }

    @Test
    fun `a short time window plus a radius still finds the change`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.worldLog.append(broke(1, at(1, 70, 1), by = steve, epochMillis = 1_000))
            val region = LookupRegion(
                world, minChunkX = 0, maxChunkX = 0, minChunkZ = 0, maxChunkZ = 0,
                minX = 0, maxX = 16, minY = 60, maxY = 80, minZ = 0, maxZ = 16,
            )
            val (worlds, _) = stack.worldLog.queryTogether(
                stack.log,
                LookupFilter(since = 0, until = 3_600_000L, region = region, limit = Int.MAX_VALUE),
            )
            assertEquals(listOf(1L), worlds.map { it.seq.raw })
        }
    }

    @Test
    fun `a time-bounded region query skips older history in the same chunks`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val pos = at(5, 70, 5)
            for (i in 1L..2_000L) stack.worldLog.append(broke(i, pos, epochMillis = i))
            stack.worldLog.append(broke(2_001, pos, epochMillis = 4_000_000))

            val nearby = LookupRegion(
                world, 0, 0, 0, 0,
                minX = 0, maxX = 10, minY = 60, maxY = 80, minZ = 0, maxZ = 10,
            )
            assertEquals(
                listOf(2_001L),
                stack.worldLog.query(
                    LookupFilter(region = nearby, since = 3_600_000, limit = Int.MAX_VALUE),
                ).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a block box inside a chunk does not restore the rest of the column`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(5, 70, 5)))
            stack.worldLog.append(broke(2, at(5, 10, 5)))

            val around = LookupRegion(
                world, 0, 0, 0, 0,
                minX = 0, maxX = 10, minY = 60, maxY = 80, minZ = 0, maxZ = 10,
            )
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(region = around)).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `a world filter does not leak into other dimensions`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val nether = WorldId(UUID(0L, 2L))
            stack.worldLog.append(broke(1, at(1, 70, 1)))
            stack.worldLog.append(broke(2, BlockPos(nether, 1, 70, 1)))

            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(world = world)).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `an actor query finds only what that player changed`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(1, 70, 1), by = player(1)))
            stack.worldLog.append(broke(2, at(2, 70, 2), by = player(2)))
            stack.worldLog.append(broke(3, at(3, 70, 3), by = player(1)))

            assertEquals(
                listOf(3L, 1L),
                stack.worldLog.query(LookupFilter(holders = setOf(player(1)))).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `i colon minecraft stone finds the same blocks as i colon stone`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(1, 70, 1)))
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(material = "minecraft:stone")).map { it.seq.raw },
            )
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(material = "stone")).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `an action filter separates what was placed from what was broken`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(1, 70, 1)))
            stack.worldLog.append(
                WorldChange(
                    Seq(2), ActionKind.BLOCK_PLACE, CauseKind.PLAYER_ACTION, player(1), 2, at(2, 70, 2),
                    ChangeSubject.Block(BlockShape.AIR, stone),
                )
            )

            assertEquals(
                listOf(2L),
                stack.worldLog.query(LookupFilter(actions = setOf(ActionKind.BLOCK_PLACE))).map { it.seq.raw },
            )
        }
    }

    @Test
    fun `the two logs share their indexes without ever seeing each other's records`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.worldLog.append(broke(1, at(1, 70, 1), by = steve, epochMillis = 100))
            stack.log.append(
                Transaction(
                    TxnId(2), Seq(2), 200, CauseKind.PLAYER_ACTION, steve,
                    listOf(Flow(diamond, Quantity(1), HolderId.Block(world, 1, 70, 1), steve, FlowKind.MOVE)),
                )
            )
            stack.worldLog.append(broke(3, at(1, 70, 1), by = steve, epochMillis = 300))

            val byActor = LookupFilter(holders = setOf(steve))
            assertEquals(listOf(3L, 1L), stack.worldLog.query(byActor).map { it.seq.raw })
            assertEquals(listOf(2L), stack.log.query(byActor).map { it.seq.raw })

            val chunk = LookupRegion(world, 0, 0, 0, 0)
            assertEquals(listOf(3L, 1L), stack.worldLog.query(LookupFilter(region = chunk)).map { it.seq.raw })
            assertEquals(listOf(2L), stack.log.query(LookupFilter(region = chunk)).map { it.seq.raw })

            assertEquals(listOf(3L, 1L), stack.worldLog.query(LookupFilter()).map { it.seq.raw })
            assertEquals(listOf(2L), stack.log.query(LookupFilter()).map { it.seq.raw })
        }
    }

    @Test
    fun `the same block state is interned once, however many blocks wear it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val cobble = BlockShape(BlockDataKey("minecraft:cobblestone"))
            for (seq in 1L..64L) stack.worldLog.append(broke(seq, at(seq.toInt(), 70, 0), before = cobble))

            val interned = stack.storage.read {
                var count = 0
                scan(
                    byteArrayOf(
                        com.tracel.storage.codec.Keys.INTERN_FORWARD,
                        com.tracel.storage.codec.Keys.NS_BLOCK_DATA
                    )
                )
                    .use { cursor -> while (cursor.next()) count++ }
                count
            }
            assertEquals(2, interned, "cobblestone and air, once each — not once per block")
            assertTrue(stack.worldLog.at(at(7, 70, 0)).single().let { it.seq.raw == 7L })
        }
    }

    @Test
    fun `bookkeeping is not lookup history`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            stack.worldLog.append(broke(1, at(1, 70, 1), by = steve, epochMillis = 100))
            stack.worldLog.append(
                WorldChange(
                    Seq(2), ActionKind.BLOCK_PLACE, CauseKind.ROLLBACK, steve, 200, at(1, 70, 1),
                    ChangeSubject.Block(BlockShape.AIR, stone),
                )
            )
            stack.worldLog.append(
                WorldChange(
                    Seq(3), ActionKind.BLOCK_BREAK, CauseKind.INVOLUTION, steve, 300, at(1, 70, 1),
                    ChangeSubject.Block(stone, BlockShape.AIR),
                )
            )

            assertEquals(listOf(1L), stack.worldLog.query(LookupFilter()).map { it.seq.raw })
            assertEquals(listOf(1L), stack.worldLog.query(LookupFilter(holders = setOf(steve))).map { it.seq.raw })
            assertEquals(
                listOf(1L),
                stack.worldLog.query(LookupFilter(region = LookupRegion(world, 0, 0, 0, 0))).map { it.seq.raw },
            )
            val spatialKeys = stack.storage.read {
                var n = 0
                scan(byteArrayOf(com.tracel.storage.codec.Keys.SPATIAL)).use { cursor -> while (cursor.next()) n++ }
                n
            }
            assertEquals(1, spatialKeys, "a restore must not add spatial keys for the next query to walk")
            assertEquals(3, stack.worldLog.at(at(1, 70, 1)).size, "inspect-at-block still sees the raw record")
        }
    }

    @Test
    fun `appending the same sequence twice is refused`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.worldLog.append(broke(1, at(0, 0, 0)))
            val failure = runCatching { stack.worldLog.append(broke(1, at(0, 0, 0))) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "expected append-only refusal, got $failure")
        }
    }

    @Test
    fun `an entity change round-trips and is findable by its own uuid`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val boat = UUID(7, 7)
            val shape = com.tracel.model.world.EntityShape(
                com.tracel.model.world.EntityTypeKey("minecraft:chest_boat"),
                10.5, 64.0, -3.25, 90f, 0f,
                com.tracel.model.world.EntityExtras.Opaque(byteArrayOf(9, 9, 9)),
            )
            val change = WorldChange(
                Seq(1), ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, player(1), 42L,
                at(10, 64, -4),
                ChangeSubject.Entity(boat, shape.type, shape, null),
            )
            stack.worldLog.append(change)

            val read = stack.worldLog.at(at(10, 64, -4)).single()
            val subject = read.subject as ChangeSubject.Entity
            assertEquals(boat, subject.entity)
            assertEquals(shape.type, subject.type)
            assertEquals(shape.extras, subject.before?.extras)
            assertEquals(10.5, subject.before?.x)
            assertEquals(90f, subject.before?.yaw)
            assertEquals(null, subject.after)
        }
    }

    @Test
    fun `an entity spawn with no nbt still round-trips a shape the planner can invert`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val boat = UUID(8, 8)
            val type = com.tracel.model.world.EntityTypeKey("minecraft:oak_boat")
            val change = WorldChange(
                Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, player(1), 42L,
                at(4, 70, -2),
                ChangeSubject.Entity(boat, type, null, com.tracel.model.world.EntityShape(type, 4.5, 70.0, -1.5)),
            )
            stack.worldLog.append(change)

            val read = stack.worldLog.at(at(4, 70, -2)).single()
            val subject = read.subject as ChangeSubject.Entity
            assertEquals(null, subject.before, "a spawn has no before")
            assertTrue(subject.after != null, "empty extras used to decode as no shape, and rollback planned nothing")
            assertEquals(type, subject.after?.type)
            assertEquals(4.5, subject.after?.x)
            assertEquals(-1.5, subject.after?.z)
            assertEquals(boat, subject.entity)
        }
    }

    @Test
    fun `a chest boat's shape never carries its cargo`(@TempDir dir: Path) = runTest {
        Stack(dir).use {
            val fields = com.tracel.model.world.EntityShape::class.java.declaredFields.map { it.type.name } +
                    BlockShape::class.java.declaredFields.map { it.type.name }
            assertTrue(
                fields.none { it.contains("Inventory") || it.contains("ItemStack") || it.contains("ItemKey") },
                "a shape holding items is a shape a rollback could duplicate from: $fields",
            )
        }
    }

    @Test
    fun `a purge clears the world log too, so the reset counter does not collide`(@TempDir directory: Path) = runTest {
        Stack(directory).use { stack ->
            stack.worldCapture.record(
                BlockEdits(
                    ActionKind.BLOCK_BREAK,
                    CauseKind.PLAYER_ACTION,
                    player(1),
                    1_000L,
                    listOf(
                        BlockEdit(
                            BlockPos(world, 1, 2, 3),
                            BlockShape(BlockDataKey("minecraft:stone")),
                            BlockShape.AIR
                        )
                    ),
                )
            )
            assertEquals(1, stack.worldLog.at(BlockPos(world, 1, 2, 3)).size)

            purgeAll(stack.storage)
            assertTrue(stack.worldLog.at(BlockPos(world, 1, 2, 3)).isEmpty(), "the world log is part of the history")

            stack.worldCapture.record(
                BlockEdits(
                    ActionKind.BLOCK_BREAK,
                    CauseKind.PLAYER_ACTION,
                    player(1),
                    2_000L,
                    listOf(
                        BlockEdit(
                            BlockPos(world, 1, 2, 3),
                            BlockShape(BlockDataKey("minecraft:dirt")),
                            BlockShape.AIR
                        )
                    ),
                )
            )
            assertEquals(1, stack.worldLog.at(BlockPos(world, 1, 2, 3)).size)
        }
    }

    @Test
    fun `a purge does not reset the numbering`(@TempDir directory: Path) = runTest {
        Stack(directory).use { stack ->
            val before = stack.counters.nextSeq().raw
            val beforeTxn = stack.counters.nextTxnId().raw

            purgeAll(stack.storage)

            assertTrue(
                stack.counters.nextSeq().raw > before,
                "a sequence handed out after a purge must be past every one handed out before it",
            )
            assertTrue(stack.counters.nextTxnId().raw > beforeTxn, "and so must a transaction id")
        }
    }

    @Test
    fun `the numbering keeps climbing across a purge and a reopen`(@TempDir directory: Path) = runTest {
        val reached = Stack(directory).use { stack ->
            repeat(600) { stack.counters.nextTxnId() }
            purgeAll(stack.storage)
            stack.counters.nextTxnId().raw
        }

        Stack(directory).use { stack ->
            assertTrue(
                stack.counters.nextTxnId().raw > reached,
                "reopening after a purge must not hand back an id the purged session already used",
            )
        }
    }

    @Test
    fun `a query big enough to batch its reads returns exactly what reading one at a time does`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val count = 900
                for (seq in 1L..count) stack.worldLog.append(
                    broke(
                        seq,
                        at((seq % 40).toInt(), 70, (seq / 40).toInt()),
                        epochMillis = seq
                    )
                )

                val region = LookupRegion(world, minChunkX = 0, maxChunkX = 3, minChunkZ = 0, maxChunkZ = 2)
                val batched = stack.worldLog.query(LookupFilter(region = region, limit = Int.MAX_VALUE))
                val oneAtATime = stack.worldLog.query(LookupFilter(region = region, limit = 255))

                assertEquals(count, batched.size, "the batched path must find every change")
                assertEquals(
                    oneAtATime.map { it.seq.raw },
                    batched.take(255).map { it.seq.raw },
                    "both paths must agree, newest first, on the rows they share",
                )
                assertEquals(count.toLong(), batched.first().seq.raw, "newest first")
                assertEquals(1L, batched.last().seq.raw, "oldest last")
            }
        }

    @Test
    fun `a batched query whose sequences are scattered still returns them all`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            var inside = 0
            for (seq in 1L..6_000L) {
                val far = seq % 50L != 0L
                stack.worldLog.append(broke(seq, at(if (far) 5_000 else (seq % 40).toInt(), 70, 0), epochMillis = seq))
                if (!far) inside++
            }

            val region = LookupRegion(world, minChunkX = 0, maxChunkX = 3, minChunkZ = 0, maxChunkZ = 0)
            val found = stack.worldLog.query(LookupFilter(region = region, limit = Int.MAX_VALUE))

            assertEquals(inside, found.size, "sparse or dense, a query returns the same rows")
            assertTrue(found.all { it.seq.raw % 50L == 0L }, "and only those rows")
        }
    }

    @Test
    fun `a wide region reads whole chunk columns and still stops exactly at its edge`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (cz in -6..6) {
                stack.worldLog.append(broke((cz + 10).toLong(), at(0, 70, cz * 16), epochMillis = (cz + 10).toLong()))
            }

            val region = LookupRegion(world, minChunkX = -300, maxChunkX = 300, minChunkZ = -2, maxChunkZ = 3)
            val found = stack.worldLog.query(LookupFilter(region = region, limit = Int.MAX_VALUE))

            assertEquals(
                listOf(-2, -1, 0, 1, 2, 3).map { (it + 10).toLong() }.sorted(),
                found.map { it.seq.raw }.sorted(),
                "every chunk inside the z range and not one outside it",
            )
        }
    }

    @Test
    fun `a region query answering off the index agrees with one that reads every record`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val sign = BlockShape(
                BlockDataKey("minecraft:oak_sign[rotation=4]"),
                BlockExtras.Opaque(byteArrayOf(1, 2, 3)),
            )
            val boat = UUID(7, 7)
            val boatShape = com.tracel.model.world.EntityShape(
                com.tracel.model.world.EntityTypeKey("minecraft:chest_boat"),
                5.5, 70.0, 5.25, 90f, 0f,
            )
            for (seq in 1L..40L) stack.worldLog.append(broke(seq, at(5, 70, (seq % 8).toInt()), epochMillis = seq))
            stack.worldLog.append(
                WorldChange(
                    Seq(41), ActionKind.BLOCK_BREAK, CauseKind.PLAYER_ACTION, player(2), 41L,
                    at(6, 70, 6), ChangeSubject.Block(sign, BlockShape.AIR),
                )
            )
            stack.worldLog.append(
                WorldChange(
                    Seq(42), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, player(3), 42L,
                    at(5, 70, 5), ChangeSubject.Entity(boat, boatShape.type, boatShape, null),
                )
            )

            val filter = LookupFilter(region = LookupRegion(world, 0, 0, 0, 0), limit = Int.MAX_VALUE)
            val (inlined, _) = stack.worldLog.queryTogether(stack.log, filter)

            assertEquals(stack.worldLog.query(filter), inlined, "every field, not just the sequence")
            assertEquals(42, inlined.size)
        }
    }

    @Test
    fun `an actor filter is applied to rows the index answered on its own`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            for (seq in 1L..30L) {
                val by = if (seq % 2 == 0L) steve else player(2)
                stack.worldLog.append(broke(seq, at(5, 70, (seq % 4).toInt()), by = by, epochMillis = seq))
            }

            val filter = LookupFilter(
                region = LookupRegion(world, 0, 0, 0, 0),
                holders = setOf(steve),
                limit = Int.MAX_VALUE,
            )
            val (found, _) = stack.worldLog.queryTogether(stack.log, filter)

            assertEquals((30L downTo 1L step 2).toList(), found.map { it.seq.raw })
            assertTrue(found.all { it.causedBy == steve }, "the holder comes off the row, so it has to be right")
        }
    }

    @Test
    fun `a material filter over a dense range is still applied when the fetch is one scan`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val dirt = BlockShape(BlockDataKey("minecraft:dirt"))
                for (seq in 1L..100L) {
                    val before = if (seq % 2 == 1L) stone else dirt
                    stack.worldLog.append(broke(seq, at(0, 70, 0), before = before, epochMillis = seq))
                }

                val found = stack.worldLog.query(LookupFilter(material = "minecraft:stone", limit = Int.MAX_VALUE))

                assertEquals((99L downTo 1L step 2).toList(), found.map { it.seq.raw })
            }
        }
}
