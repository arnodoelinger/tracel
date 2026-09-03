package com.tracel.tests.rollback

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.EntityShape
import com.tracel.model.world.EntityTypeKey
import com.tracel.model.world.WorldChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class StructurePlannerTest {
    private val world = WorldId(UUID(0L, 1L))
    private val here = BlockPos(world, 10, 70, -3)
    private val steve = HolderId.Player(UUID(0L, 1L))

    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val cobble = BlockShape(BlockDataKey("minecraft:cobblestone"))
    private val chest = BlockShape(BlockDataKey("minecraft:chest[facing=north]"))

    private fun block(seq: Long, before: BlockShape, after: BlockShape, at: BlockPos = here) = WorldChange(
        Seq(seq), ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, steve, seq, at,
        ChangeSubject.Block(before, after),
    )

    @Suppress("SameParameterValue")
    private fun entity(seq: Long, uuid: UUID, before: EntityShape?, after: EntityShape?) = WorldChange(
        Seq(seq), ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, steve, seq, here,
        ChangeSubject.Entity(uuid, (before ?: after)!!.type, before, after),
    )

    private fun boat() = EntityShape(EntityTypeKey("minecraft:chest_boat"), 10.5, 70.0, -3.5)

    @Test
    @Suppress("KotlinMisorderedAssertEqualsArguments")
    fun `a coordinate touched several times restores to the state before the first change`() {
        // Newest first, the order every query returns
        val changes = listOf(
            block(3, cobble, BlockShape.AIR),
            block(2, BlockShape.AIR, cobble),
            block(1, stone, BlockShape.AIR),
        )

        val (create, destroy) = StructurePlanner().plan(changes)

        assertEquals(emptyList<StructureStep>(), destroy)
        val step = create.single() as StructureStep.SetBlock
        assertEquals(stone, step.target, "the state before the *first* matched change, not the last")
        assertEquals(BlockShape.AIR, step.expected, "what the newest matched change left standing")
    }

    @Test
    @Suppress("KotlinMisorderedAssertEqualsArguments")
    fun `a block placed inside the window is removed, and removal waits for the destroy phase`() {
        val (create, destroy) = StructurePlanner().plan(listOf(block(1, BlockShape.AIR, cobble)))

        assertEquals(emptyList<StructureStep>(), create)
        val step = destroy.single() as StructureStep.SetBlock
        assertEquals(BlockShape.AIR, step.target)
        assertEquals(cobble, step.expected)
    }

    @Test
    fun `a broken container is restored in the create phase, so the ledger has somewhere to deliver`() {
        val (create, destroy) = StructurePlanner().plan(listOf(block(1, chest, BlockShape.AIR)))

        assertTrue(destroy.isEmpty())
        assertEquals(chest, (create.single() as StructureStep.SetBlock).target)
    }

    @Test
    fun `a coordinate that ended up where it started needs no step at all`() {
        val changes = listOf(block(2, cobble, stone), block(1, stone, cobble))

        val (create, destroy) = StructurePlanner().plan(changes)

        assertTrue(create.isEmpty() && destroy.isEmpty(), "nothing to undo when nothing net changed")
    }

    @Test
    fun `two coordinates are planned independently`() {
        val there = BlockPos(world, 11, 70, -3)
        val changes = listOf(block(2, stone, BlockShape.AIR, there), block(1, cobble, BlockShape.AIR))

        val (create, _) = StructurePlanner().plan(changes)

        assertEquals(setOf(here, there), create.map { it.at }.toSet())
    }

    @Test
    fun `an entity destroyed inside the window is spawned back`() {
        val uuid = UUID(5, 5)
        val (create, destroy) = StructurePlanner().plan(listOf(entity(1, uuid, boat(), null)))

        assertTrue(destroy.isEmpty())
        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(uuid, step.entity)
        assertEquals(boat(), step.shape)
    }

    @Test
    fun `a chest placed then exploded in the same window is put back`() {
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Block(chest, BlockShape.AIR),
            ),
            block(1, BlockShape.AIR, chest),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(destroy.isEmpty())
        assertEquals(chest, (create.single() as StructureStep.SetBlock).target)
    }

    @Test
    fun `an entity placed then exploded in the same window is spawned back`() {
        val uuid = UUID(9, 9)
        val hull = boat()
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Entity(uuid, hull.type, hull, null),
            ),
            entity(1, uuid, null, hull),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(destroy.isEmpty())
        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(uuid, step.entity)
        assertEquals(hull, step.shape)
    }

    @Test
    fun `an entity spawned inside the window is taken away, carrying the shape an undo needs`() {
        val uuid = UUID(6, 6)
        val (create, destroy) = StructurePlanner().plan(listOf(entity(1, uuid, null, boat())))

        assertTrue(create.isEmpty())
        val step = destroy.single() as StructureStep.RemoveEntity
        assertEquals(boat(), step.shape, "without the shape, undoing this could not put the boat back")
    }

    @Test
    fun `a falling block spawned in the window is removed in create, so undo respawns it after the block is gone`() {
        val uuid = UUID(8, 8)
        val falling = EntityShape(EntityTypeKey("minecraft:falling_block"), 10.5, 70.0, -3.5)
        val (create, destroy) = StructurePlanner().plan(listOf(entity(1, uuid, null, falling)))

        assertTrue(destroy.isEmpty(), "destroying it last would drop the gravel as an item")
        val step = create.single() as StructureStep.RemoveEntity
        assertEquals(falling, step.shape)
    }

    @Test
    fun `every structural step is its own inverse twice over`() {
        val uuid = UUID(7, 7)
        val steps = listOf(
            StructureStep.SetBlock(here, stone, BlockShape.AIR),
            StructureStep.SpawnEntity(here, uuid, boat()),
            StructureStep.RemoveEntity(here, uuid, boat()),
        )

        for (step in steps) assertEquals(step, step.inverse().inverse())
    }

    @Test
    @Suppress("KotlinMisorderedAssertEqualsArguments")
    fun `inverting a block edit swaps what it restores for what it expected`() {
        val step = StructureStep.SetBlock(here, stone, BlockShape.AIR)
        val inverse = step.inverse() as StructureStep.SetBlock

        assertEquals(BlockShape.AIR, inverse.target)
        assertEquals(stone, inverse.expected)
    }

    @Test
    fun `several coordinates interleaved keep their own two ends`() {
        val positions = (0 until 3).map { BlockPos(world, it, 70, 0) }
        val changes = ArrayList<WorldChange>()
        var seq = 9L
        for (round in 0 until 3) {
            for (at in positions) {
                val before = if (round == 2) stone else cobble
                val after = if (round == 0) chest else cobble
                changes += block(seq--, before, after, at)
            }
        }

        val (create, destroy) = StructurePlanner().plan(changes)

        assertEquals(emptyList<StructureStep>(), destroy)
        assertEquals(3, create.size)
        for (step in create.map { it as StructureStep.SetBlock }) {
            assertEquals(stone, step.target, "${step.at} took the oldest change's before")
            assertEquals(chest, step.expected, "${step.at} took the newest change's after")
        }
        assertEquals(positions.toSet(), create.map { (it as StructureStep.SetBlock).at }.toSet())
    }
}
