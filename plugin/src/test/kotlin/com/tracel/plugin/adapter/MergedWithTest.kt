package com.tracel.plugin.adapter

import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.mergedWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergedWithTest {
    private val planks = ItemKey("minecraft:oak_planks")
    private val log = ItemKey("minecraft:oak_log")

    @Test
    fun `counts on both sides add up rather than shadowing each other`() {
        assertEquals(
            mapOf(planks to 12L, log to 3L),
            mapOf(planks to 8L, log to 3L).mergedWith(mapOf(planks to 4L)),
        )
    }

    @Test
    fun `an empty side changes nothing`() {
        val pockets = mapOf(planks to 8L)
        assertEquals(pockets, pockets.mergedWith(emptyMap()))
        assertEquals(pockets, emptyMap<ItemKey, Long>().mergedWith(pockets))
    }
}
