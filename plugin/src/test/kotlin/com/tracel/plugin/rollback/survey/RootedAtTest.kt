package com.tracel.plugin.rollback.survey

import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.LotId
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootedAtTest {
    private val lot = LotId(1)
    private val world = WorldId(UUID(0L, 1L))
    private val frame = HolderId.Entity(UUID(7L, 7L))
    private val chest = HolderId.Block(world, 0, 64, 0)
    private val steve = HolderId.Player(UUID(0L, 2L))
    private val nowhere = HolderId.Source(SourceKind.UNATTRIBUTED)

    @Test
    fun `cargo blown off a hull this rollback is putting back stays on the hull`() {
        val roots = mutableMapOf<LotId, HolderId>()
        val keep = setOf(frame.uuid)
        roots.rootedAt(lot, frame, keep)
        roots.rootedAt(lot, steve, keep)
        assertEquals(frame, roots[lot], "undoing the blast puts the sword back in the frame")
    }

    @Test
    fun `the earliest real holder wins over a newer one`() {
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(lot, chest)
        roots.rootedAt(lot, steve)
        assertEquals(steve, roots[lot], "put it back where the window found it")
    }

    @Test
    fun `a creative mint does not overwrite the frame it was minted into`() {
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(lot, frame)
        roots.rootedAt(lot, nowhere)
        assertEquals(frame, roots[lot], "the sword goes back in the frame, not to a non-place")
    }

    @Test
    fun `a lot that was only ever minted keeps its source and is compensated away`() {
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(lot, nowhere)
        assertEquals(nowhere, roots[lot], "nothing to put it back into — rolling back its creation removes it")
    }

    @Test
    fun `a real holder still wins over a source filed first`() {
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(lot, nowhere)
        roots.rootedAt(lot, chest)
        assertEquals(chest, roots[lot], "the burn was only ever a fallback")
    }

    @Test
    fun `a burned split of an in-window mint goes back to the source, not the chest`() {
        val parent = LotId(1)
        val burned = LotId(2)
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(burned, chest)
        roots.rootedAt(parent, nowhere)
        roots.inheritMintedBurns(
            sittingAt = mapOf(burned to HolderId.Sink(SinkKind.UNATTRIBUTED), parent to chest),
            parentOf = mapOf(burned to parent),
        )
        assertEquals(nowhere, roots[burned], "reminting into the chest cancels taking the rest out")
        assertEquals(nowhere, roots[parent])
    }

    @Test
    fun `a frame drop does not inherit the creative mint`() {
        val parent = LotId(1)
        val drop = LotId(2)
        val roots = mutableMapOf<LotId, HolderId>()
        roots.rootedAt(drop, frame)
        roots.rootedAt(parent, nowhere)
        roots.inheritMintedBurns(
            sittingAt = mapOf(drop to HolderId.ItemEntity(UUID(1L, 1L)), parent to frame),
            parentOf = mapOf(drop to parent),
        )
        assertEquals(frame, roots[drop], "the sword still goes back in the frame")
    }

    @Test
    fun `loot is not delivered into a chest the window both created and erased`() {
        val target = RollbackTarget.PerRoot(mapOf(lot to chest))
        val redirected = target.awayFromAirToAir(setOf(BlockPos(world, 0, 64, 0)))
        val dest = (redirected as RollbackTarget.PerRoot).byRoot.getValue(lot)
        assertTrue(dest is HolderId.Source, "creative loot vanishes; a standing chest is not this case")
    }
}
