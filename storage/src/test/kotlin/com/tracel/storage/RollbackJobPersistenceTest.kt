package com.tracel.storage

import com.tracel.engine.rollback.plan.LotContribution
import com.tracel.engine.rollback.job.RollbackJobRecord
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
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

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
}
