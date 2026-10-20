package com.tracel.plugin.command.presenter.line

import com.tracel.api.history.BlockRecord
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.RecordKind
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.api.read.Actors
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.command.presenter.ItemPresenter
import com.tracel.plugin.command.presenter.PlayerReport
import com.tracel.plugin.specifics.block.AIR
import com.tracel.tests.support.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class BlockPlacePresenterTest {
    private val steveUuid = UUID.randomUUID()
    private val steve = HolderId.Player(steveUuid)
    private val pos = BlockPos(Fixtures.world, 10, 64, 10)
    private val snow = BlockShape(BlockDataKey("minecraft:snow[layers=1]"))
    private val craftingTable = BlockShape(BlockDataKey("minecraft:crafting_table"))
    private val dirt = BlockShape(BlockDataKey("minecraft:dirt"))
    private val farmland = BlockShape(BlockDataKey("minecraft:farmland[moisture=0]"))

    private fun record(change: WorldChange): BlockRecord =
        ApiConversions.record(change, HistoryQuery(kinds = RecordKind.entries.toSet()), emptyMap()) as BlockRecord

    @Test
    fun `placing workbench on snow is presented as placed even if action is BLOCK_CHANGE`() {
        val change = WorldChange(
            seq = Seq(1L),
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = steve,
            epochMillis = 1000L,
            at = pos,
            subject = ChangeSubject.Block(before = snow, after = craftingTable),
        )

        val line = ChangeLinePresenter.logged(record(change), Actors.NONE)
        assertEquals("placed", line.title)
        assertEquals(ItemPresenter.PLUS, line.mark)
    }

    @Test
    fun `placing block in air is presented as placed`() {
        val change = WorldChange(
            seq = Seq(1L),
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = steve,
            epochMillis = 1000L,
            at = pos,
            subject = ChangeSubject.Block(before = AIR, after = craftingTable),
        )

        val line = ChangeLinePresenter.logged(record(change), Actors.NONE)
        assertEquals("placed", line.title)
        assertEquals(ItemPresenter.PLUS, line.mark)
    }

    @Test
    fun `tilling dirt to farmland keeps tilled title`() {
        val change = WorldChange(
            seq = Seq(1L),
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = steve,
            epochMillis = 1000L,
            at = pos,
            subject = ChangeSubject.Block(before = dirt, after = farmland),
        )

        val line = ChangeLinePresenter.logged(record(change), Actors.NONE)
        assertEquals("tilled", line.title)
    }

    @Test
    fun `block replacement in player report counts as placed`() {
        val changes = listOf(
            WorldChange(
                seq = Seq(1L),
                action = ActionKind.BLOCK_CHANGE,
                cause = CauseKind.PLAYER_ACTION,
                causedBy = steve,
                epochMillis = 1000L,
                at = pos,
                subject = ChangeSubject.Block(before = snow, after = craftingTable),
            )
        )

        val report = PlayerReport.of(steveUuid, changes.mapNotNull { ApiConversions.record(it, HistoryQuery(kinds = RecordKind.entries.toSet()), emptyMap()) })
        assertEquals(1, report.placed)
        assertEquals(0, report.changed)
    }
}
