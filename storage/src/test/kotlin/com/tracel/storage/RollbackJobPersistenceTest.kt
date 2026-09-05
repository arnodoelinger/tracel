package com.tracel.storage

import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.LotContribution
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.BlockExtras
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.EntityShape
import com.tracel.model.world.EntityTypeKey
import com.tracel.storage.codec.Keys
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RollbackJobPersistenceTest {
    private val world = WorldId(UUID(0L, 1L))
    private val at = BlockPos(world, 10, 70, -3)
    private val boat = UUID(9, 9)

    private val plan = RollbackPlan(
        listOf(
            RollbackStep.Take(LotId(1), Quantity(3), block(0, 64, 0)),
            RollbackStep.Mint(LotId(2), Quantity(1), SinkKind.LAVA),
            RollbackStep.Unmake(LotId(3), listOf(LotContribution(LotId(4), Quantity(9))), TxnId(7), player(1)),
        )
    )

    private val create = listOf(
        StructureStep.SetBlock(
            at,
            BlockShape(BlockDataKey("minecraft:chest[facing=north]"), BlockExtras.Opaque(byteArrayOf(1, 2, 3))),
            BlockShape.AIR,
        ),
        StructureStep.SpawnEntity(
            at,
            boat,
            EntityShape(EntityTypeKey("minecraft:chest_boat"), 10.5, 70.25, -3.75, 90f, -12.5f),
        ),
    )

    private val destroy = listOf(
        StructureStep.SetBlock(BlockPos(world, 11, 70, -3), BlockShape.AIR, BlockShape(BlockDataKey("minecraft:cobblestone"))),
    )

    @Test
    fun `a job with both halves round-trips whole`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val record = RollbackJobRecord(RollbackJobId(1), plan, RollbackTarget.Uniform(player(1)), create, destroy)
            stack.jobs.save(record)

            val read = stack.jobs.find(RollbackJobId(1))!!
            assertEquals(plan.steps, read.plan.steps)
            assertEquals(RollbackTarget.Uniform(player(1)), read.target)
            assertEquals(create, read.create)
            assertEquals(destroy, read.destroy)
        }
    }

    @Test
    fun `a per-root target round-trips every destination it named`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val target = RollbackTarget.PerRoot(
                mapOf(LotId(1) to block(0, 64, 0), LotId(2) to player(2), LotId(4) to block(5, 5, 5))
            )
            stack.jobs.save(RollbackJobRecord(RollbackJobId(2), plan, target, create, destroy))

            assertEquals(target, stack.jobs.find(RollbackJobId(2))!!.target)
        }
    }

    @Test
    fun `a lot that was split still knows where its material went, after a restart`(@TempDir dir: Path) = runTest {
        val chest = block(0, 64, 0)
        val root = LotId(100)
        val leaf = LotId(1)
        val split = RollbackPlan(
            listOf(RollbackStep.Take(leaf, Quantity(3), player(1))),
            rootOf = mapOf(leaf to root),
        )
        val target = RollbackTarget.PerRoot(mapOf(root to chest))

        Stack(dir).use { stack -> stack.jobs.save(RollbackJobRecord(RollbackJobId(9), split, target)) }

        Stack(dir).use { stack ->
            val read = stack.jobs.find(RollbackJobId(9))!!
            // rootOf is gone, as it always was. The destination has to survive without it.
            assertEquals(chest, read.target.destinationFor(read.plan, leaf))
        }
    }

    @Test
    fun `an entity's position survives to the last bit`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            // An entity that comes back a thousandth of a block off is an entity somebody notices
            val exact = EntityShape(EntityTypeKey("minecraft:item_frame"), -0.1, 63.99999999, 1.0 / 3.0, 179.5f, -89.25f)
            val steps = listOf(StructureStep.SpawnEntity(at, boat, exact))
            stack.jobs.save(RollbackJobRecord(RollbackJobId(3), RollbackPlan(emptyList()), RollbackTarget.PerRoot(emptyMap()), steps))

            assertEquals(exact, (stack.jobs.find(RollbackJobId(3))!!.create.single() as StructureStep.SpawnEntity).shape)
        }
    }

    @Test
    fun `a job with no structural half reads back as none, not as a failure`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.jobs.save(RollbackJobRecord(RollbackJobId(4), plan, RollbackTarget.Uniform(player(1))))

            val read = stack.jobs.find(RollbackJobId(4))!!
            assertEquals(emptyList<StructureStep>(), read.create)
            assertEquals(emptyList<StructureStep>(), read.destroy)
        }
    }

    @Test
    fun `the undo stack is newest-first, survives a reopen, and pops`(@TempDir directory: Path) = runTest {
        val first = RollbackJobId(1)
        val second = RollbackJobId(2)

        Stack(directory).use { stack ->
            stack.jobs.save(RollbackJobRecord(first, plan, RollbackTarget.Uniform(player(1))))
            stack.jobs.save(RollbackJobRecord(second, plan, RollbackTarget.Uniform(player(1))))
            assertEquals(listOf(second, first), stack.jobs.undoable(limit = 10), "newest first")
        }

        Stack(directory).use { stack ->
            assertEquals(listOf(second), stack.jobs.undoable(limit = 1), "the stack outlives the process")
            assertEquals(true, stack.jobs.isUndoable(second))

            stack.jobs.markUndone(second)
            assertEquals(false, stack.jobs.isUndoable(second), "an undone job is not offered again")
            assertEquals(listOf(first), stack.jobs.undoable(limit = 10), "undo again walks one further back")

            stack.jobs.markUndone(first)
            assertEquals(emptyList<RollbackJobId>(), stack.jobs.undoable(limit = 10))
        }
    }

    @Test
    fun `halves written apart still read back as one list, across run boundaries`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            fun blocks(from: Int, count: Int) = (from until from + count).map { i ->
                StructureStep.SetBlock(
                    BlockPos(world, i, 70, 0),
                    BlockShape(BlockDataKey("minecraft:stone")),
                    BlockShape.AIR,
                )
            }

            val many = blocks(0, 700)
            val gone = blocks(10_000, 600)
            val record = RollbackJobRecord(RollbackJobId(5), plan, RollbackTarget.Uniform(player(1)), many, gone)
            stack.jobs.finish(stack.jobs.begin(record), gone)

            val read = stack.jobs.find(RollbackJobId(5))!!
            assertEquals(many, read.create)
            assertEquals(gone, read.destroy)
        }
    }

    @Test
    fun `a save that never finished is not a job at all`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val record = RollbackJobRecord(RollbackJobId(6), plan, RollbackTarget.Uniform(player(1)), create, destroy)
            stack.jobs.begin(record)

            assertNull(stack.jobs.find(RollbackJobId(6)))
            assertFalse(stack.jobs.isUndoable(RollbackJobId(6)))
        }
    }

    @Test
    fun `a job's blocks are packed by section and still all come back`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val blocks = (0 until 2).flatMap { s ->
                (0 until 4096).map { i ->
                    StructureStep.SetBlock(
                        BlockPos(world, i and 15, 64 + s * 16 + (i shr 8), (i shr 4) and 15),
                        BlockShape(BlockDataKey("minecraft:stone")),
                        BlockShape.AIR,
                    )
                }
            }.shuffled(java.util.Random(7).let { rng -> kotlin.random.Random(rng.nextLong()) })
            val many = blocks + StructureStep.SpawnEntity(at, boat, EntityShape(EntityTypeKey("minecraft:boat"), 1.0, 2.0, 3.0))

            stack.jobs.save(RollbackJobRecord(RollbackJobId(7), plan, RollbackTarget.Uniform(player(1)), many, destroy))

            val read = stack.jobs.find(RollbackJobId(7))!!
            assertEquals(many.size, read.create.size)
            assertEquals(many.toSet(), read.create.toSet())
            assertEquals(destroy, read.destroy)
        }
    }

    @Test
    fun `packing a job's blocks by section costs a fraction of a step apiece`(@TempDir dir: Path) = runTest {
        val packed = dir.resolve("packed")
        val apiece = dir.resolve("apiece")
        fun crater(from: Int, count: Int) = (from until from + count).map { i ->
            StructureStep.SetBlock(
                BlockPos(world, i and 15, 64 + (i shr 8), (i shr 4) and 15),
                BlockShape(BlockDataKey("minecraft:stone")),
                BlockShape.AIR,
            )
        }

        Stack(packed).use { stack ->
            stack.jobs.save(RollbackJobRecord(RollbackJobId(1), plan, RollbackTarget.Uniform(player(1)), crater(0, 4096)))
        }
        Stack(apiece).use { stack ->
            crater(0, 4096).forEachIndexed { i, step ->
                stack.jobs.save(RollbackJobRecord(RollbackJobId(i + 1L), plan, RollbackTarget.Uniform(player(1)), listOf(step)))
            }
        }

        val packedBytes = bytesIn(packed)
        val apieceBytes = bytesIn(apiece)
        println("4096 job steps: $packedBytes bytes packed, $apieceBytes bytes a step each")
        assertTrue(apieceBytes > packedBytes * 5, "$apieceBytes against $packedBytes")
    }


    @Test
    fun `an undone job leaves nothing behind`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val id = RollbackJobId(11)
            val target = RollbackTarget.PerRoot(mapOf(LotId(1) to block(0, 64, 0), LotId(2) to player(2)))
            stack.jobs.save(RollbackJobRecord(id, plan, target, create, destroy))
            val before = bytesIn(dir)

            stack.jobs.markUndone(id)

            assertNull(stack.jobs.find(id), "an undone job is not a job any more")
            assertFalse(stack.jobs.isUndoable(id))
            assertEquals(emptyList<RollbackJobId>(), stack.jobs.undoable(limit = 10))
            assertTrue(bytesIn(dir) >= 0 && before > 0)
            assertEquals(0, keysUnder(stack, Keys.rbStructPrefix(id.raw)))
            assertEquals(0, keysUnder(stack, Keys.rbStepPrefix(id.raw)))
            assertEquals(0, keysUnder(stack, Keys.rbTargetPrefix(id.raw)))
        }
    }

    @Test
    fun `the undo stack keeps twenty jobs and forgets the rest entirely`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val depth = com.tracel.engine.rollback.job.RollbackJobRepository.UNDO_DEPTH
            for (i in 1..depth + 5) {
                stack.jobs.save(RollbackJobRecord(RollbackJobId(i.toLong()), plan, RollbackTarget.Uniform(player(1)), create))
            }

            val stack20 = stack.jobs.undoable(limit = 100)
            assertEquals(depth, stack20.size, "the stack is capped")
            assertEquals(RollbackJobId((depth + 5).toLong()), stack20.first(), "newest first")

            for (i in 1..5) {
                assertNull(stack.jobs.find(RollbackJobId(i.toLong())), "job $i should have been evicted")
                assertEquals(0, keysUnder(stack, Keys.rbStructPrefix(i.toLong())))
            }
            val kept = stack.jobs.find(RollbackJobId(6))!!
            assertEquals(create, kept.create)
        }
    }

    private suspend fun keysUnder(stack: Stack, prefix: ByteArray): Int = stack.storage.read {
        var n = 0
        scan(prefix).use { cursor -> while (cursor.next()) n++ }
        n
    }

    private fun bytesIn(dir: Path): Long =
        java.nio.file.Files.walk(dir).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) }
                .mapToLong { java.nio.file.Files.size(it) }
                .sum()
        }
}
