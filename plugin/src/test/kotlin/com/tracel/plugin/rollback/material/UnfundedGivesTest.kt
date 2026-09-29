package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ItemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class UnfundedGivesTest {
    private val stand = ItemKey("ARMOR_STAND", null)
    private val tunic = ItemKey("LEATHER_CHESTPLATE", null)
    private val player = HolderId.Player(UUID(1L, 1L))
    private val pileA = HolderId.ItemEntity(UUID(2L, 1L))
    private val pileB = HolderId.ItemEntity(UUID(2L, 2L))
    private val placed = HolderId.PlacedEntity(UUID(3L, 3L))
    private val source = HolderId.Source(SourceKind.UNATTRIBUTED)

    @Test
    fun `nothing short means every give goes out as planned`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(pileA to mapOf(stand to 1L), player to mapOf(tunic to 2L))
        val out = withholdUnfunded(gives, emptyMap())
        assertEquals(gives, out.funded)
        assertTrue(out.withheld.isEmpty())
        assertTrue(out.unspent.isEmpty())
    }

    @Test
    fun `a take that fell short by one drops one pile, not both`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(pileA to mapOf(stand to 1L), pileB to mapOf(stand to 1L))
        val out = withholdUnfunded(gives, mapOf(stand to 1L))
        assertEquals(mapOf<HolderId, Map<ItemKey, Long>>(pileB to mapOf(stand to 1L)), out.funded)
        assertEquals(mapOf<HolderId, Map<ItemKey, Long>>(pileA to mapOf(stand to 1L)), out.withheld)
        assertTrue(out.unspent.isEmpty())
    }

    @Test
    fun `piles are cut before a player`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to 1L), pileA to mapOf(stand to 1L))
        val out = withholdUnfunded(gives, mapOf(stand to 1L))
        assertEquals(setOf<HolderId>(pileA), out.withheld.keys)
        assertEquals(setOf<HolderId>(player), out.funded.keys)
    }

    @Test
    fun `only the short key is cut and a stack is trimmed, not dropped`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to 3L, tunic to 1L))
        val out = withholdUnfunded(gives, mapOf(stand to 2L))
        assertEquals(mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to 1L, tunic to 1L)), out.funded)
        assertEquals(mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to 2L)), out.withheld)
    }

    @Test
    fun `a holder that is no place to keep items neither absorbs the cut nor loses its give`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(placed to mapOf(stand to 1L), source to mapOf(stand to 1L))
        val out = withholdUnfunded(gives, mapOf(stand to 1L))
        assertEquals(gives, out.funded)
        assertTrue(out.withheld.isEmpty())
        assertEquals(mapOf(stand to 1L), out.unspent, "nothing physical to cut it from")
    }

    @Test
    fun `a shortfall bigger than the gives is reported as unspent`() {
        val gives = mapOf<HolderId, Map<ItemKey, Long>>(pileA to mapOf(stand to 1L))
        val out = withholdUnfunded(gives, mapOf(stand to 3L))
        assertTrue(out.funded.isEmpty())
        assertEquals(mapOf(stand to 2L), out.unspent)
    }

    @Test
    fun `a holder that reports nothing itemised took nothing`() {
        val takes = mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to -2L, tunic to -1L))
        val short = shortfallOf(takes, listOf(player to ApplyResult.Failed("gone")))
        assertEquals(mapOf(stand to 2L, tunic to 1L), short)
    }

    @Test
    fun `an itemised failure counts only what it names, capped at what was asked`() {
        val takes = mapOf<HolderId, Map<ItemKey, Long>>(player to mapOf(stand to -2L, tunic to -1L))
        val short = shortfallOf(takes, listOf(player to ApplyResult.Failed("short", mapOf(stand to 1L, tunic to 9L))))
        assertEquals(mapOf(stand to 1L, tunic to 1L), short)
    }

    @Test
    fun `shortfalls add up across holders and a good take adds none`() {
        val takes = mapOf<HolderId, Map<ItemKey, Long>>(
            player to mapOf(stand to -1L),
            pileA to mapOf(stand to -1L),
            pileB to mapOf(stand to -1L),
        )
        val short = shortfallOf(
            takes,
            listOf(
                player to ApplyResult.Failed("short", mapOf(stand to 1L)),
                pileA to ApplyResult.Failed("gone"),
                pileB to ApplyResult.Ok,
            ),
        )
        assertEquals(mapOf(stand to 2L), short)
    }
}
