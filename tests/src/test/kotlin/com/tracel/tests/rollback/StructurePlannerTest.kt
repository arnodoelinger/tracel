@file:Suppress("KotlinMisorderedAssertEqualsArguments")

package com.tracel.tests.rollback

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.vehicle
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.model.world.WorldChange
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructurePlannerTest {
    private val world = WorldId(UUID(0L, 1L))
    private val here = BlockPos(world, 10, 70, -3)
    private val steve = HolderId.Player(UUID(0L, 1L))

    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val cobble = BlockShape(BlockDataKey("minecraft:cobblestone"))
    private val chest = BlockShape(BlockDataKey("minecraft:chest[facing=north]"))
    private val redstone = BlockShape(BlockDataKey("minecraft:redstone_block"))
    private val sand = BlockShape(BlockDataKey("minecraft:sand"))
    private val fire = BlockShape(BlockDataKey("minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]"))

    private val tnt = BlockShape(BlockDataKey("minecraft:tnt[unstable=false]"))

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

    private val sponge = BlockShape(BlockDataKey("minecraft:sponge"))
    private val wetSponge = BlockShape(BlockDataKey("minecraft:wet_sponge"))
    private val water = BlockShape(BlockDataKey("minecraft:water[level=0]"))

    @Test
    fun `a sponge is removed as the wet sponge it has become, and its lake comes back`() {
        val drank = BlockPos(world, 11, 70, -3)
        val (create, destroy) = StructurePlanner().plan(
            listOf(
                block(1, BlockShape.AIR, sponge),
                block(2, sponge, wetSponge),
                block(3, water, BlockShape.AIR, at = drank),
            )
        )

        assertEquals(
            listOf(StructureStep.SetBlock(here, BlockShape.AIR, wetSponge)),
            destroy,
            "the sponge goes, and what stands there now is a wet one",
        )
        assertEquals(
            listOf(StructureStep.SetBlock(drank, water, BlockShape.AIR)),
            create,
            "and the water it drank is put back",
        )
    }

    @Test
    fun `a sponge older than the window is dried, not taken away`() {
        val (create, destroy) = StructurePlanner().plan(
            listOf(block(1, sponge, wetSponge))
        )

        assertEquals(listOf(StructureStep.SetBlock(here, sponge, wetSponge)), create)
        assertTrue(destroy.isEmpty(), "it was not placed in this window, so it is not removed")
    }

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
    fun `a chest placed then exploded in the same window is not put back`() {
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Block(chest, BlockShape.AIR),
            ),
            block(1, BlockShape.AIR, chest),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(create.isEmpty() && destroy.isEmpty(), "ten days ago the chest was not there")
    }

    @Test
    fun `a redstone block placed then exploded in the same window is not put back`() {
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Block(redstone, BlockShape.AIR),
            ),
            block(1, BlockShape.AIR, redstone),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(create.isEmpty() && destroy.isEmpty())
        assertEquals(setOf(here), StructurePlanner().cellsAirToAir(changes))
    }

    @Test
    fun `sand blown up then lit still restores to sand, even if the list is not newest-first`() {
        val sandToFire = WorldChange(
            Seq(1), ActionKind.BLOCK_PLACE, CauseKind.EXPLOSION, null, 1, here,
            ChangeSubject.Block(sand, fire),
        )
        val airToFire = WorldChange(
            Seq(2), ActionKind.BLOCK_PLACE, CauseKind.EXPLOSION, null, 2, here,
            ChangeSubject.Block(BlockShape.AIR, fire),
        )
        for (order in listOf(listOf(airToFire, sandToFire), listOf(sandToFire, airToFire))) {
            val (create, destroy) = StructurePlanner().plan(order)
            assertTrue(destroy.isEmpty(), "putting fire out to air drops the sand")
            val step = create.single() as StructureStep.SetBlock
            assertEquals(sand, step.target, "before the blast it was sand")
            assertEquals(fire, step.expected)
        }
    }

    @Test
    fun `tnt placed then detonated in the same window is not stood back up`() {
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Block(tnt, BlockShape.AIR),
            ),
            block(1, BlockShape.AIR, tnt),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(create.isEmpty())
        assertTrue(destroy.isEmpty())
    }

    @Test
    fun `tnt that stood there before the window is put back`() {
        val changes = listOf(
            WorldChange(
                Seq(1), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 1, here,
                ChangeSubject.Block(tnt, BlockShape.AIR),
            ),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(destroy.isEmpty())
        assertEquals(tnt, (create.single() as StructureStep.SetBlock).target)
    }

    @Test
    fun `a frame hung then exploded in the same window is spawned back`() {
        val uuid = UUID(9, 9)
        val hull = EntityShape(EntityTypeKey("minecraft:glow_item_frame"), 10.5, 70.0, -3.5)
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Entity(uuid, hull.type, hull, null),
            ),
            WorldChange(
                Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 1, here,
                ChangeSubject.Entity(uuid, hull.type, null, hull),
            ),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(destroy.isEmpty())
        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(uuid, step.entity)
        assertEquals(hull, step.shape)
    }

    @Test
    fun `grass broken to hang a frame is not put back on top of the restored frame`() {
        val uuid = UUID(15, 15)
        val hull = EntityShape(EntityTypeKey("minecraft:glow_item_frame"), 10.5, 70.0, -3.5)
        val grass = BlockShape(BlockDataKey("minecraft:short_grass"))
        val changes = listOf(
            WorldChange(
                Seq(3), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, steve, 3, here,
                ChangeSubject.Entity(uuid, hull.type, hull, null),
            ),
            WorldChange(
                Seq(2), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 2, here,
                ChangeSubject.Entity(uuid, hull.type, null, hull),
            ),
            block(1, grass, BlockShape.AIR),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(destroy.isEmpty())
        assertEquals(1, create.size, "grass in the frame's cell would pop it")
        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(uuid, step.entity)
    }

    @Test
    fun `grass broken over a killed sheep is put back, and the sheep with it`() {
        val uuid = UUID(16, 16)
        val sheep = EntityShape(EntityTypeKey("minecraft:sheep"), 10.5, 70.0, -3.5)
        val grass = BlockShape(BlockDataKey("minecraft:short_grass"))
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Entity(uuid, sheep.type, sheep, null),
            ),
            block(1, grass, BlockShape.AIR),
        )
        val (create, destroy) = StructurePlanner().plan(changes)

        assertTrue(destroy.isEmpty())
        assertEquals(2, create.size, "the grass and the sheep both come back")
        assertTrue(create.any { it is StructureStep.SetBlock && it.target == grass })
        assertTrue(create.any { it is StructureStep.SpawnEntity && it.shape == sheep })
    }

    @Test
    fun `a painting hung in the window is taken down in the create phase, before the blocks return`() {
        val painting = UUID(31, 31)
        val shape = EntityShape(EntityTypeKey("minecraft:painting"), 10.5, 70.0, -3.5)
        val (create, destroy) = StructurePlanner().plan(
            listOf(
                WorldChange(
                    Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 1, here,
                    ChangeSubject.Entity(painting, shape.type, null, shape),
                ),
            ),
        )

        assertTrue(
            create.any { it is StructureStep.RemoveEntity && it.entity == painting },
            "left in destroy, the wall is rebuilt around a painting that is still hanging on it — " +
                "vanilla pops it, the item drops beside the one the ledger is handing back, and the " +
                "removal that arrives afterwards finds nothing to record",
        )
        assertTrue(destroy.none { it is StructureStep.RemoveEntity && it.entity == painting })
    }

    @Test
    fun `an item frame stays in the destroy phase, behind the withdrawal that empties it`() {
        val frame = UUID(32, 32)
        val shape = EntityShape(EntityTypeKey("minecraft:item_frame"), 10.5, 70.0, -3.5)
        val (create, destroy) = StructurePlanner().plan(
            listOf(
                WorldChange(
                    Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 1, here,
                    ChangeSubject.Entity(frame, shape.type, null, shape),
                ),
            ),
        )

        assertTrue(destroy.any { it is StructureStep.RemoveEntity && it.entity == frame })
        assertTrue(create.none { it is StructureStep.RemoveEntity && it.entity == frame })
    }

    @Test
    fun `a cow tied up in the window is planned back loose, and the knot goes with it`() {
        val cow = UUID(21, 21)
        val knot = UUID(22, 22)
        val loose = EntityShape(EntityTypeKey("minecraft:cow"), 10.5, 70.0, -3.5)
        val tied = loose.copy(extras = EntityExtras.Leashed(knot, null))
        val knotShape = EntityShape(EntityTypeKey("minecraft:leash_knot"), 12.5, 70.0, -3.5)
        val changes = listOf(
            WorldChange(
                Seq(1), ActionKind.ENTITY_CHANGE, CauseKind.PLAYER_ACTION, steve, 1, here,
                ChangeSubject.Entity(cow, loose.type, loose, tied),
            ),
            WorldChange(
                Seq(2), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 2, here,
                ChangeSubject.Entity(knot, knotShape.type, null, knotShape),
            ),
        )
        val (create, destroy) = StructurePlanner().plan(changes)

        assertEquals(1, create.size)
        assertTrue(create.any { it is StructureStep.SpawnEntity && it.shape == loose })
        assertTrue(destroy.any { it is StructureStep.RemoveEntity && it.entity == knot })
    }

    @Test
    fun `a cow untied in the window is planned back onto the knot it was on`() {
        val cow = UUID(23, 23)
        val knot = UUID(24, 24)
        val loose = EntityShape(EntityTypeKey("minecraft:cow"), 10.5, 70.0, -3.5)
        val tied = loose.copy(extras = EntityExtras.Leashed(knot, null))
        val (create, _) = StructurePlanner().plan(
            listOf(
                WorldChange(
                    Seq(1), ActionKind.ENTITY_CHANGE, CauseKind.PLAYER_ACTION, steve, 1, here,
                    ChangeSubject.Entity(cow, loose.type, tied, loose),
                ),
            ),
        )

        val step = create.filterIsInstance<StructureStep.SpawnEntity>().single()
        assertEquals(knot, (step.shape.extras as EntityExtras.Leashed).holder, "the other end has to travel")
    }

    @Test
    fun `undoing a change in place puts the shape back rather than deleting the entity`() {
        val cow = UUID(41, 41)
        val knot = UUID(42, 42)
        val loose = EntityShape(EntityTypeKey("minecraft:mooshroom"), 10.5, 70.0, -3.5)
        val tied = loose.copy(extras = EntityExtras.Leashed(knot, null))
        val (create, _) = StructurePlanner().plan(
            listOf(
                WorldChange(
                    Seq(1), ActionKind.ENTITY_CHANGE, CauseKind.PLAYER_ACTION, steve, 1, here,
                    ChangeSubject.Entity(cow, loose.type, loose, tied),
                ),
            ),
        )

        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(loose, step.shape, "the rollback unties it")
        assertEquals(tied, step.expected, "and remembers what it found")

        val undone = step.inverse() as StructureStep.SpawnEntity
        assertEquals(tied, undone.shape, "the undo ties it up again")
        assertEquals(loose, undone.expected)
        assertEquals(step, undone.inverse(), "and the pair is a round trip")
    }

    @Test
    fun `undoing a resurrection still takes the entity away`() {
        val uuid = UUID(43, 43)
        val sheep = EntityShape(EntityTypeKey("minecraft:sheep"), 10.5, 70.0, -3.5)
        val (create, _) = StructurePlanner().plan(
            listOf(
                WorldChange(
                    Seq(1), ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, steve, 1, here,
                    ChangeSubject.Entity(uuid, sheep.type, sheep, null),
                ),
            ),
        )

        val step = create.single() as StructureStep.SpawnEntity
        assertEquals(null, step.expected)
        assertTrue(step.inverse() is StructureStep.RemoveEntity)
    }

    @Test
    fun `two pigs thrown out of a boat are planned back into it`() {
        val boat = UUID(31, 31)
        val hull = EntityShape(EntityTypeKey("minecraft:boat"), 10.5, 70.0, -3.5)
        fun pig(id: Long): Pair<UUID, EntityShape> =
            UUID(id, id) to EntityShape(EntityTypeKey("minecraft:pig"), 10.5, 70.0, -3.5)

        val (first, firstShape) = pig(32)
        val (second, secondShape) = pig(33)
        val seated = { it: EntityShape -> it.copy(extras = EntityExtras.Riding(boat, null)) }
        val changes = listOf(
            WorldChange(
                Seq(1), ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, steve, 1, here,
                ChangeSubject.Entity(boat, hull.type, hull, null),
            ),
            WorldChange(
                Seq(2), ActionKind.ENTITY_CHANGE, CauseKind.WORLD, null, 2, here,
                ChangeSubject.Entity(first, firstShape.type, seated(firstShape), firstShape),
            ),
            WorldChange(
                Seq(3), ActionKind.ENTITY_CHANGE, CauseKind.WORLD, null, 3, here,
                ChangeSubject.Entity(second, secondShape.type, seated(secondShape), secondShape),
            ),
        )
        val (create, _) = StructurePlanner().plan(changes)

        val spawns = create.filterIsInstance<StructureStep.SpawnEntity>()
        assertEquals(3, spawns.size, "the boat and both riders")
        assertEquals(
            listOf(boat, boat),
            spawns.filter { it.entity != boat }.map { it.shape.extras.vehicle },
            "both riders name the boat they sat in",
        )
    }

    @Test
    fun `a frame hung then punched in the same window is not spawned back`() {
        val uuid = UUID(13, 13)
        val hull = EntityShape(EntityTypeKey("minecraft:glow_item_frame"), 10.5, 70.0, -3.5)
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, steve, 2, here,
                ChangeSubject.Entity(uuid, hull.type, hull, null),
            ),
            WorldChange(
                Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 1, here,
                ChangeSubject.Entity(uuid, hull.type, null, hull),
            ),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(create.isEmpty() && destroy.isEmpty(), "punching it is not an explosion")
    }

    @Test
    fun `primed tnt that exploded is not stood back up even if spawn is in the window`() {
        val uuid = UUID(11, 11)
        val hull = EntityShape(EntityTypeKey("minecraft:tnt"), 10.5, 70.0, -3.5)
        val changes = listOf(
            WorldChange(
                Seq(2), ActionKind.ENTITY_REMOVE, CauseKind.EXPLOSION, steve, 2, here,
                ChangeSubject.Entity(uuid, hull.type, hull, null),
            ),
            WorldChange(
                Seq(1), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, steve, 1, here,
                ChangeSubject.Entity(uuid, hull.type, null, hull),
            ),
        )
        val (create, destroy) = StructurePlanner().plan(changes)
        assertTrue(create.isEmpty() && destroy.isEmpty())
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
        for ((at, target, expected) in create.map { it as StructureStep.SetBlock }) {
            assertEquals(stone, target, "$at took the oldest change's before")
            assertEquals(chest, expected, "$at took the newest change's after")
        }
        assertEquals(positions.toSet(), create.map { (it as StructureStep.SetBlock).at }.toSet())
    }
}
