package com.tracel.plugin.util.whereabouts

import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.plugin.util.EntityWhereabouts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class EntityWhereaboutsTest {
    private val world = WorldId(UUID(0L, 1L))
    private fun pos(x: Int) = HolderId.Block(world, x, 64, 0)
    private fun id(n: Long) = UUID(0L, n)

    @Test
    fun `a remembered position survives the entity it belongs to`() {
        val where = EntityWhereabouts()
        where.remember(id(1), pos(5))

        assertEquals(pos(5), where.at(id(1)), "the whole point is answering after the entity is gone")
    }

    @Test
    fun `remembering the same entity again moves it, without growing the store`() {
        val where = EntityWhereabouts(capacity = 2)
        where.remember(id(1), pos(1))
        where.remember(id(1), pos(2))
        where.remember(id(2), pos(3))

        assertEquals(pos(2), where.at(id(1)), "the newest position wins")
        assertEquals(pos(3), where.at(id(2)), "and the repeat did not count against capacity")
    }

    @Test
    fun `past capacity the oldest positions go, the newest stay`() {
        val where = EntityWhereabouts(capacity = 3)
        for (n in 1L..10L) where.remember(id(n), pos(n.toInt()))

        assertNull(where.at(id(1)), "a pile that died first is the one nobody is coming back for")
        for (n in 8L..10L) assertNotNull(where.at(id(n)), "the newest $n must still be answerable")
    }

    @Test
    fun `the unbounded default keeps everything, which is what hulls need`() {
        val where = EntityWhereabouts()
        for (n in 1L..1_000L) where.remember(id(n), pos(n.toInt()))

        assertEquals(pos(1), where.at(id(1)))
        assertEquals(pos(1000), where.at(id(1000)))
    }

    @Test
    fun `forgetting an entity does not leave the eviction queue growing without bound`() {
        val where = EntityWhereabouts(capacity = 4)
        for (n in 1L..1_000L) {
            where.remember(id(n), pos(1))
            where.forget(id(n))
        }

        assertNull(where.at(id(1)))
        where.remember(id(9_999L), pos(7))
        assertEquals(pos(7), where.at(id(9_999L)), "still usable after the churn")
    }
}
