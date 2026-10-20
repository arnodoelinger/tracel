package com.tracel.plugin.api.rollback

import com.tracel.api.result.Res
import com.tracel.api.result.getOrNull
import com.tracel.api.rollback.RollbackError
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BorrowsTest {
    private val world = UUID(0, 1)
    private val spawn = LookupArea.Around(BlockPlace(world, 0, 64, 0), 10)
    private val nextDoor = LookupArea.Around(BlockPlace(world, 15, 64, 0), 10)
    private val farAway = LookupArea.Around(BlockPlace(world, 500, 64, 0), 10)

    @Test
    fun `two holds never overlap`() {
        val borrows = Borrows()
        assertTrue(borrows.take("Arena", spawn) is Res.Ok)
        assertEquals(Res.Err(RollbackError.Borrowed("Arena")), borrows.take("AntiGrief", nextDoor))
        assertTrue(borrows.take("AntiGrief", farAway) is Res.Ok)
    }

    @Test
    fun `a released hold expires and frees its area`() {
        val borrows = Borrows()
        val lease = borrows.take("Arena", spawn).getOrNull()!!
        borrows.release(lease)
        assertTrue(lease.expired, "what was made from it stops working")
        assertNull(borrows.heldAgainst("AntiGrief", spawn))
        assertTrue(borrows.take("AntiGrief", nextDoor) is Res.Ok)
    }

    @Test
    fun `a plugin is never held against itself`() {
        val borrows = Borrows()
        borrows.take("Arena", spawn)
        assertNull(borrows.heldAgainst("Arena", nextDoor))
        assertEquals("Arena", borrows.heldAgainst("AntiGrief", nextDoor))
    }
}
