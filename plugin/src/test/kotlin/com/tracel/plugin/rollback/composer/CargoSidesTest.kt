package com.tracel.plugin.rollback.composer

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class CargoSidesTest {
    private val frame = UUID(7L, 7L)
    private val stand = UUID(8L, 8L)
    private val sword = ItemKey("DIAMOND_SWORD", null)
    private val chest = HolderId.Block(WorldId(UUID(0L, 1L)), 0, 64, 0)

    @Test
    fun `a hull being filled is fed by the ledger and comes back stripped`() {
        val deltas = mapOf<HolderId, Map<ItemKey, Long>>(HolderId.Entity(frame) to mapOf(sword to 1L))
        assertEquals(setOf(frame), filledByLedger(deltas))
        assertEquals(emptySet<UUID>(), emptiedByLedger(deltas), "nothing is being taken out of it")
    }

    @Test
    fun `a hull being emptied is the one a removal may refuse over`() {
        val deltas = mapOf<HolderId, Map<ItemKey, Long>>(HolderId.Entity(stand) to mapOf(sword to -1L))
        assertEquals(setOf(stand), emptiedByLedger(deltas))
        assertEquals(emptySet<UUID>(), filledByLedger(deltas), "nothing is being delivered into it")
    }

    @Test
    fun `a hull the ledger never mentions belongs to neither side`() {
        val deltas = mapOf<HolderId, Map<ItemKey, Long>>(chest to mapOf(sword to -1L))
        assertEquals(emptySet<UUID>(), emptiedByLedger(deltas))
        assertEquals(emptySet<UUID>(), filledByLedger(deltas))
    }

    @Test
    fun `a placed hull is not its own cargo`() {
        val deltas = mapOf<HolderId, Map<ItemKey, Long>>(
            HolderId.PlacedEntity(frame) to mapOf(ItemKey("ITEM_FRAME", null) to -1L),
        )
        assertEquals(emptySet<UUID>(), emptiedByLedger(deltas), "the hull is not the cargo")
    }
}
