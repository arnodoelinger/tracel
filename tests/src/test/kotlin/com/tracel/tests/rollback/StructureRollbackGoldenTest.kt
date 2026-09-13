package com.tracel.tests.rollback

import com.tracel.annotations.CauseKind
import com.tracel.engine.rollback.structure.StructurePlanner
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.structuralPartnerOf
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StructureRollbackGoldenTest {
    private val world = WorldId(UUID(0L, 0L))
    private val here = BlockPos(world, 0, 64, 0)
    private val east = BlockPos(world, 1, 64, 0)
    private val south = BlockPos(world, 0, 64, 1)
    private val pushed = BlockPos(world, 2, 64, 0)
    private val steve = HolderId.Player(UUID(0L, 1L))

    private val air = BlockShape.AIR
    private val stone = shape("stone")
    private val single = shape("chest[facing=north,type=single,waterlogged=false]")
    private val left = shape("chest[facing=north,type=left,waterlogged=false]")
    private val right = shape("chest[facing=north,type=right,waterlogged=false]")
    private val foot = shape("red_bed[facing=south,occupied=false,part=foot]")
    private val head = shape("red_bed[facing=south,occupied=false,part=head]")
    private val retracted = shape("piston[extended=false,facing=east]")
    private val extended = shape("piston[extended=true,facing=east]")
    private val pistonHead = shape("piston_head[facing=east,short=false,type=normal]")

    private fun shape(state: String) = BlockShape(BlockDataKey("minecraft:$state"))

    private fun block(seq: Long, before: BlockShape, after: BlockShape, at: BlockPos = here) = WorldChange(
        Seq(seq), ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, steve, seq, at,
        ChangeSubject.Block(before, after),
    )

    private fun assertGolden(
        changes: List<WorldChange>,
        create: List<StructureStep>,
        destroy: List<StructureStep>,
        pairing: List<Pair<BlockPos?, BlockPos?>>,
    ) {
        val (actualCreate, actualDestroy) = StructurePlanner().plan(changes)

        assertEquals(create, actualCreate, "ordered create, including target and expected")
        assertEquals(destroy, actualDestroy, "ordered destroy, including target and expected")
        assertEquals(
            pairing,
            changes.map { change ->
                val subject = change.subject as ChangeSubject.Block
                structuralPartnerOf(change.at, subject.before) to structuralPartnerOf(change.at, subject.after)
            },
            "before and after partners, in input order",
        )
    }

    @Test
    fun `joining an old single chest restores the single and removes only the new half`() {
        assertGolden(
            changes = listOf(
                block(2, air, right, east),
                block(1, single, left),
            ),
            create = listOf(StructureStep.SetBlock(here, single, left)),
            destroy = listOf(StructureStep.SetBlock(east, air, right)),
            pairing = listOf(null to here, null to east),
        )
    }

    @Test
    fun `breaking one double chest half restores it and reconnects the surviving single`() {
        assertGolden(
            changes = listOf(
                block(2, right, single, east),
                block(1, left, air),
            ),
            create = listOf(
                StructureStep.SetBlock(here, left, air),
                StructureStep.SetBlock(east, right, single),
            ),
            destroy = emptyList(),
            pairing = listOf(here to null, east to null),
        )
    }

    @Test
    fun `breaking both double chest halves restores their original paired states`() {
        assertGolden(
            changes = listOf(
                block(3, single, air, east),
                block(2, right, single, east),
                block(1, left, air),
            ),
            create = listOf(
                StructureStep.SetBlock(here, left, air),
                StructureStep.SetBlock(east, right, air),
            ),
            destroy = emptyList(),
            pairing = listOf(null to null, here to null, east to null),
        )
    }

    @Test
    fun `placing and joining both chest halves removes both and expects their newest states`() {
        assertGolden(
            changes = listOf(
                block(3, single, left),
                block(2, air, right, east),
                block(1, air, single),
            ),
            create = emptyList(),
            destroy = listOf(
                StructureStep.SetBlock(here, air, left),
                StructureStep.SetBlock(east, air, right),
            ),
            pairing = listOf(null to east, null to here, null to null),
        )
    }

    @Test
    fun `a double chest placed and completely broken within the window has no steps`() {
        assertGolden(
            changes = listOf(
                block(6, single, air, east),
                block(2, air, right, east),
                block(4, left, air),
                block(1, air, single),
                block(5, right, single, east),
                block(3, single, left),
            ),
            create = emptyList(),
            destroy = emptyList(),
            pairing = listOf(null to null, null to here, east to null, null to null, here to null, null to east),
        )
    }

    @Test
    fun `a matched chest half names its partner but does not add an unmatched cell to the plan`() {
        assertGolden(
            changes = listOf(block(1, left, air)),
            create = listOf(StructureStep.SetBlock(here, left, air)),
            destroy = emptyList(),
            pairing = listOf(east to null),
        )
    }

    @Test
    fun `a broken waterlogged trapped double chest keeps both full block states`() {
        val trappedLeft = shape("trapped_chest[facing=north,type=left,waterlogged=true]")
        val trappedRight = shape("trapped_chest[facing=north,type=right,waterlogged=true]")
        val water = shape("water[level=0]")

        assertGolden(
            changes = listOf(
                block(2, trappedRight, water, east),
                block(1, trappedLeft, water),
            ),
            create = listOf(
                StructureStep.SetBlock(here, trappedLeft, water),
                StructureStep.SetBlock(east, trappedRight, water),
            ),
            destroy = emptyList(),
            pairing = listOf(here to null, east to null),
        )
    }

    @Test
    fun `placing a bed removes the foot then the head with reciprocal expected pairing`() {
        assertGolden(
            changes = listOf(
                block(2, air, head, south),
                block(1, air, foot),
            ),
            create = emptyList(),
            destroy = listOf(
                StructureStep.SetBlock(here, air, foot),
                StructureStep.SetBlock(south, air, head),
            ),
            pairing = listOf(null to here, null to south),
        )
    }

    @Test
    fun `breaking a bed creates the foot then the head with reciprocal target pairing`() {
        assertGolden(
            changes = listOf(
                block(2, head, air, south),
                block(1, foot, air),
            ),
            create = listOf(
                StructureStep.SetBlock(here, foot, air),
                StructureStep.SetBlock(south, head, air),
            ),
            destroy = emptyList(),
            pairing = listOf(here to null, south to null),
        )
    }

    @Test
    fun `replacing a south facing bed with an east facing bed restores the old head cell`() {
        val eastFoot = shape("red_bed[facing=east,occupied=false,part=foot]")
        val eastHead = shape("red_bed[facing=east,occupied=false,part=head]")

        assertGolden(
            changes = listOf(
                block(4, air, eastHead, east),
                block(1, foot, air),
                block(3, air, eastFoot),
                block(2, head, air, south),
            ),
            create = listOf(
                StructureStep.SetBlock(here, foot, eastFoot),
                StructureStep.SetBlock(south, head, air),
            ),
            destroy = listOf(StructureStep.SetBlock(east, air, eastHead)),
            pairing = listOf(null to here, south to null, null to east, here to null),
        )
    }

    @Test
    fun `extending a piston restores the retracted base before removing the head`() {
        assertGolden(
            changes = listOf(
                block(2, air, pistonHead, east),
                block(1, retracted, extended),
            ),
            create = listOf(StructureStep.SetBlock(here, retracted, extended)),
            destroy = listOf(StructureStep.SetBlock(east, air, pistonHead)),
            pairing = listOf(null to here, null to east),
        )
    }

    @Test
    fun `retracting a piston recreates the extended base then its head`() {
        assertGolden(
            changes = listOf(
                block(2, pistonHead, air, east),
                block(1, extended, retracted),
            ),
            create = listOf(
                StructureStep.SetBlock(here, extended, retracted),
                StructureStep.SetBlock(east, pistonHead, air),
            ),
            destroy = emptyList(),
            pairing = listOf(here to null, east to null),
        )
    }

    @Test
    fun `a piston push restores the stone over the head and removes the displaced stone`() {
        assertGolden(
            changes = listOf(
                block(4, air, stone, pushed),
                block(2, stone, air, east),
                block(1, retracted, extended),
                block(3, air, pistonHead, east),
            ),
            create = listOf(
                StructureStep.SetBlock(here, retracted, extended),
                StructureStep.SetBlock(east, stone, pistonHead),
            ),
            destroy = listOf(StructureStep.SetBlock(pushed, air, stone)),
            pairing = listOf(null to null, null to null, null to east, null to here),
        )
    }

    @Test
    fun `a sticky piston pull restores the head over the pulled stone and returns the stone`() {
        val stickyRetracted = shape("sticky_piston[extended=false,facing=east]")
        val stickyExtended = shape("sticky_piston[extended=true,facing=east]")
        val stickyHead = shape("piston_head[facing=east,short=false,type=sticky]")

        assertGolden(
            changes = listOf(
                block(4, air, stone, east),
                block(3, stone, air, pushed),
                block(2, stickyHead, air, east),
                block(1, stickyExtended, stickyRetracted),
            ),
            create = listOf(
                StructureStep.SetBlock(here, stickyExtended, stickyRetracted),
                StructureStep.SetBlock(east, stickyHead, stone),
                StructureStep.SetBlock(pushed, stone, air),
            ),
            destroy = emptyList(),
            pairing = listOf(null to null, null to null, here to null, east to null),
        )
    }

    @Test
    fun `breaking an extended upward piston recreates its base then its head`() {
        val above = here.copy(y = 65)
        val upExtended = shape("piston[extended=true,facing=up]")
        val upHead = shape("piston_head[facing=up,short=false,type=normal]")

        assertGolden(
            changes = listOf(
                block(2, upHead, air, above),
                block(1, upExtended, air),
            ),
            create = listOf(
                StructureStep.SetBlock(here, upExtended, air),
                StructureStep.SetBlock(above, upHead, air),
            ),
            destroy = emptyList(),
            pairing = listOf(here to null, above to null),
        )
    }

    @Test
    fun `a piston placed then extended is removed as an extended base and a head`() {
        assertGolden(
            changes = listOf(
                block(3, air, pistonHead, east),
                block(2, retracted, extended),
                block(1, air, retracted),
            ),
            create = emptyList(),
            destroy = listOf(
                StructureStep.SetBlock(here, air, extended),
                StructureStep.SetBlock(east, air, pistonHead),
            ),
            pairing = listOf(null to here, null to east, null to null),
        )
    }
}
