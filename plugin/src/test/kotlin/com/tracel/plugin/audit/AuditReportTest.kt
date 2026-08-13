package com.tracel.plugin.audit

import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuditReportTest {
    private val diamond = ItemKey("minecraft:diamond")
    private val stick = ItemKey("minecraft:stick")

    @Test
    fun `no drift when live and believed agree`() {
        assertEquals(emptyList<Drift>(), diffTotals(mapOf(diamond to 3L), mapOf(diamond to Quantity(3))))
    }

    @Test
    fun `empty on both sides is no drift`() {
        assertEquals(emptyList<Drift>(), diffTotals(emptyMap(), emptyMap()))
    }

    @Test
    fun `an item the world has but the ledger never heard of is drift against zero`() {
        assertEquals(
            listOf(Drift(diamond, live = 5L, believed = 0L)),
            diffTotals(mapOf(diamond to 5L), emptyMap()),
        )
    }

    @Test
    fun `an item the ledger believes exists but the world does not have is drift against zero`() {
        assertEquals(
            listOf(Drift(diamond, live = 0L, believed = 5L)),
            diffTotals(emptyMap(), mapOf(diamond to Quantity(5))),
        )
    }

    @Test
    fun `mismatched quantities report both sides, matching item keys are left out`() {
        val live = mapOf(diamond to 4L, stick to 2L)
        val believed = mapOf(diamond to Quantity(1), stick to Quantity(2))

        assertEquals(listOf(Drift(diamond, live = 4L, believed = 1L)), diffTotals(live, believed))
    }
}
