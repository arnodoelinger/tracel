package com.tracel.tests.log

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.world.InMemoryWorldLog
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class InMemoryWorldLogTest {
    private val world = WorldId(UUID(0L, 1L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))

    private fun broke(
        seq: Long,
        x: Int,
        z: Int,
        y: Int = 70,
        by: HolderId? = player(1),
        cause: CauseKind = CauseKind.PLAYER_ACTION
    ) =
        WorldChange(
            Seq(seq), ActionKind.BLOCK_BREAK, cause, by, seq,
            BlockPos(world, x, y, z),
            ChangeSubject.Block(stone, BlockShape.AIR),
        )

    @Test
    fun `excluded causes and a radius both apply when a player is named`() = runTest {
        val log = InMemoryWorldLog()
        val steve = player(1)
        log.append(broke(1, 5, 5, by = steve))
        log.append(broke(2, 300, 5, by = steve))
        log.append(broke(3, 6, 6, by = steve, cause = CauseKind.ROLLBACK))

        val nearby = LookupRegion(world, 0, 0, 0, 0, minX = 0, maxX = 10, minY = 60, maxY = 80, minZ = 0, maxZ = 10)
        assertEquals(
            listOf(1L),
            log.query(LookupFilter(holders = setOf(steve), region = nearby)).map { it.seq.raw },
            "a restore is not lookup history, even without excludedCauses",
        )
    }
}
