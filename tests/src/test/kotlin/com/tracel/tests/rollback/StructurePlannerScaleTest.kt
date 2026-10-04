package com.tracel.tests.rollback

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class StructurePlannerScaleTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))
    private val steve = HolderId.Player(UUID(0L, 1L))

    private val states = listOf(
        BlockShape.AIR,
        BlockShape(BlockDataKey("minecraft:stone")),
        BlockShape(BlockDataKey("minecraft:dirt")),
        BlockShape(BlockDataKey("minecraft:glass")),
        BlockShape(BlockDataKey("minecraft:oak_planks")),
    )

    private fun change(seq: Long, at: BlockPos, before: BlockShape, after: BlockShape) = WorldChange(
        Seq(seq), ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, steve, seq, at,
        ChangeSubject.Block(before, after),
    )

    private fun reference(changes: List<WorldChange>): Pair<Set<StructureStep>, Set<StructureStep>> {
        val ends = HashMap<BlockPos, Array<WorldChange>>()
        for (change in changes) {
            val slot = ends.getOrPut(change.at) { arrayOf(change, change) }
            if (change.seq.raw > slot[0].seq.raw) slot[0] = change
            if (change.seq.raw < slot[1].seq.raw) slot[1] = change
        }
        val create = HashSet<StructureStep>()
        val destroy = HashSet<StructureStep>()
        for (slot in ends.values) {
            val target = (slot[1].subject as ChangeSubject.Block).before
            val expected = (slot[0].subject as ChangeSubject.Block).after
            if (target == expected) continue
            val step = StructureStep.SetBlock(slot[1].at, target, expected)
            if (target.isAirLike) destroy += step else create += step
        }
        return create to destroy
    }

    @Test
    fun `a window past the size the tables start at plans exactly what a map keyed by position does`() {
        val random = Random(7)
        val changes = ArrayList<WorldChange>()
        var seq = 0L
        repeat(400_000) {
            val world = if (random.nextInt(10) == 0) nether else overworld
            val at = BlockPos(world, random.nextInt(-300, 300), random.nextInt(-64, 100), random.nextInt(-300, 300))
            val before = states[random.nextInt(states.size)]
            val after = states[random.nextInt(states.size)]
            changes += change(++seq, at, before, after)
        }
        changes.shuffle(random)

        val (create, destroy) = StructurePlanner().plan(changes)
        val (wantCreate, wantDestroy) = reference(changes)

        assertEquals(wantCreate.size, create.size, "no cell planned twice")
        assertEquals(wantDestroy.size, destroy.size, "no cell planned twice")
        assertEquals(wantCreate, create.toSet())
        assertEquals(wantDestroy, destroy.toSet())
    }

    @Test
    fun `coordinates no packing can hold are kept apart and merged like any other cell`() {
        val far = BlockPos(overworld, 1 shl 26, 70, 0)
        val farther = BlockPos(overworld, 2 shl 26, 70, 0)
        val stone = states[1]
        val dirt = states[2]
        val changes = listOf(
            change(3, far, dirt, BlockShape.AIR),
            change(2, far, stone, dirt),
            change(1, far, BlockShape.AIR, stone),
            change(4, farther, stone, BlockShape.AIR),
        )

        val (create, destroy) = StructurePlanner().plan(changes)

        assertTrue(destroy.isEmpty(), "the far cell was air at both ends of the window")
        assertEquals(listOf(StructureStep.SetBlock(farther, stone, BlockShape.AIR)), create)
    }

    @Test
    fun `the plan shares one shape per state`() {
        val at = { x: Int -> BlockPos(overworld, x, 70, 0) }
        val changes = (0 until 50).map { x ->
            change(
                x + 1L, at(x),
                BlockShape(BlockDataKey("minecraft:stone")),
                BlockShape(BlockDataKey("minecraft:air")),
            )
        }

        val (create, _) = StructurePlanner().plan(changes)

        val targets = create.map { (it as StructureStep.SetBlock).target }
        assertEquals(50, targets.size)
        assertTrue(targets.all { it === targets.first() }, "fifty cells, one stone")
        assertSame(targets.first(), targets.last())
    }

    @Test
    fun `a plan comes out in the order its cells sit in the world, whatever order the log gave`() {
        val random = Random(11)
        val stone = states[1]
        val cells = (0 until 2_000).map {
            BlockPos(overworld, random.nextInt(-100, 100), random.nextInt(-64, 320), random.nextInt(-100, 100))
        }.distinct()
        val changes = cells.mapIndexed { i, at -> change(i + 1L, at, stone, BlockShape.AIR) }.shuffled(random)

        val (create, _) = StructurePlanner().plan(changes)

        val order = compareBy<BlockPos>({ it.x shr 4 }, { it.z shr 4 }, { it.y }, { it.z and 15 }, { it.x and 15 })
        assertEquals(cells.sortedWith(order), create.map { it.at })
    }
}
