package com.tracel.plugin.command.suggest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PurgeSuggestTest {
    private val lists = SuggestLists(
        onlinePlayers = listOf("Alice", "Bob"),
        worldNames = listOf("world", "world_nether"),
    )

    private fun texts(line: String, known: List<String> = emptyList()) =
        PurgeSuggest.suggest(line, lists, known).map { it.text }

    @Test
    fun `the first word offers the filters and all, but not a confirm yet`() {
        assertEquals(listOf("category", "older", "world", "player", "all"), texts(""))
    }

    @Test
    fun `a filter once said is not offered again, and confirm waits for one`() {
        val after = texts("older 30d ")
        assertFalse("older" in after)
        assertFalse("all" in after, "all cannot be mixed with a filter")
        assertTrue("#confirm" in after)
    }

    @Test
    fun `values follow their word`() {
        assertEquals(listOf("blocks", "items", "containers", "events"), texts("category "))
        assertEquals(listOf("world", "world_nether"), texts("world "))
        assertEquals(listOf("Alice", "Bob", "Zed"), texts("player ", known = listOf("Alice", "Zed")))
        assertTrue("30d" in texts("older "))
    }

    @Test
    fun `a category list continues after the comma and skips what it already has`() {
        val next = PurgeSuggest.suggest("category blocks,", lists)

        assertEquals(listOf("blocks,items", "blocks,containers", "blocks,events"), next.map { it.text })
        assertEquals(listOf("items", "containers", "events"), next.map { it.tail })
    }

    @Test
    fun `a bare number is offered every unit`() {
        assertEquals(listOf("3h", "3d", "3w"), texts("older 3"))
    }
}
