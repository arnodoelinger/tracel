package com.tracel.plugin.command.suggest

import com.mojang.brigadier.suggestion.SuggestionsBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SmartLookupSuggestTest {
    private val lists = SuggestLists(
        onlinePlayers = listOf("Alice", "Bob", "Charlie"),
        worldNames = listOf("world", "world_nether", "world_the_end"),
        actionNames = listOf("block", "container", "kill", "craft"),
        itemNames = listOf("diamond", "diamond_sword", "stick", "stone"),
        blockNames = listOf("stone", "sand", "dirt"),
    )

    @Test
    fun `an empty rollback line offers the primary flags`() {
        val suggestions = RollbackSuggest.suggest("", lists)
        val flags = suggestions.map { it.text }

        assertFalse("undo" in flags)
        assertTrue("u:" in flags)
        assertTrue("t:" in flags)
        assertTrue("#preview" in flags)
        assertTrue("#blocks" in flags)
        assertTrue("#items" in flags)
        assertFalse("user:" in flags)
        assertFalse("#trace" in flags)

        val preview = suggestions.first { it.text == "#preview" }
        assertTrue(preview.tooltip!!.contains("Preview"))
    }

    @Test
    fun `lookup does not offer rollback-only flags`() {
        val flags = LookupSuggest.suggest("", lists).map { it.text }
        assertTrue("u:" in flags)
        assertTrue("scope:" in flags)
        assertTrue("#blocks" in flags)
        assertFalse("undo" in flags)
        assertFalse("#preview" in flags)
        assertFalse("#strict" in flags)
        assertFalse("l:" in flags)
    }

    @Test
    fun `flags already typed stay out of the next suggestion`() {
        val flags = RollbackSuggest.suggest("u:Alice t:1h #preview ", lists).map { it.text }
        assertFalse("u:" in flags)
        assertFalse("user:" in flags)
        assertFalse("t:" in flags)
        assertFalse("time:" in flags)
        assertFalse("#preview" in flags)
        assertTrue("i:" in flags)
        assertTrue("scope:" in flags)
        assertTrue("#blocks" in flags)
    }

    @Test
    fun `blocks and items hide each other`() {
        val withBlocks = LookupSuggest.suggest("#blocks ", lists).map { it.text }
        assertFalse("#blocks" in withBlocks)
        assertFalse("#items" in withBlocks)

        val withItems = LookupSuggest.suggest("#items ", lists).map { it.text }
        assertFalse("#items" in withItems)
        assertFalse("#blocks" in withItems)
    }

    @Test
    fun `typing a long alias still finds it`() {
        val flags = LookupSuggest.suggest("user", lists).map { it.text }
        assertEquals(listOf("user:"), flags)
    }

    @Test
    fun `comma-separated players are not offered twice`() {
        val texts = LookupSuggest.suggest("u:Alice,", lists).map { it.text }
        assertEquals(listOf("u:Alice,Bob", "u:Alice,Charlie"), texts)
    }

    @Test
    fun `comma-separated actions keep the ones that match`() {
        val texts = LookupSuggest.suggest("a:block,c", lists).map { it.text }
        assertEquals(listOf("a:block,craft", "a:block,container"), texts)
        assertEquals("Crafting", LookupSuggest.suggest("a:block,c", lists).first { it.text.endsWith("craft") }.tooltip)
    }

    @Test
    fun `a partial time or item narrows the list`() {
        val times = LookupSuggest.suggest("t:1", lists).map { it.text }
        assertTrue("t:1h" in times)
        assertTrue("t:1d" in times)
        assertTrue("t:15m" in times)

        val items = LookupSuggest.suggest("i:dia", lists).map { it.text }
        assertEquals(listOf("i:diamond", "i:diamond_sword"), items)
    }

    @Test
    fun `any radius offers blocks and chunks after the digits`() {
        val open = LookupSuggest.suggest("scope:100", lists)
        assertEquals(listOf("scope:100b", "scope:100c"), open.map { it.text })
        assertEquals(listOf("b", "c"), open.map { it.tail })

        val blocks = LookupSuggest.suggest("scope:100b", lists).map { it.text }
        assertEquals(listOf("scope:100b"), blocks)

        val chunks = LookupSuggest.suggest("scope:42c", lists).map { it.text }
        assertEquals(listOf("scope:42c"), chunks)

        val odd = LookupSuggest.suggest("scope:42", lists).map { it.text }
        assertTrue("scope:42b" in odd)
        assertTrue("scope:42c" in odd)
    }

    @Test
    fun `a typed radius is completed as just the unit`() {
        val full = "/tracel lookup scope:100"
        val builder = SuggestionsBuilder(full, "/tracel lookup ".length)
        val done = builder.reply(LookupSuggest.suggest("scope:100", lists)).join()

        assertEquals(setOf("b", "c"), done.list.map { it.text }.toSet())
        val applied = done.list.associate { it.text to it.apply(full) }
        assertEquals("${full}b", applied["b"])
        assertEquals("${full}c", applied["c"])
        assertTrue(done.list.first { it.text == "b" }.tooltip.string.contains("block"))
        assertTrue(done.list.first { it.text == "c" }.tooltip.string.contains("chunk"))
    }

    @Test
    fun `a time value offers a unit for whatever number was typed`() {
        val fortyFive = LookupSuggest.suggest("t:45", lists).map { it.text }
        assertTrue("t:45s" in fortyFive)
        assertTrue("t:45m" in fortyFive)
        assertTrue("t:45h" in fortyFive)
        assertTrue("t:45d" in fortyFive)
        assertTrue("t:45w" in fortyFive)

        val continued = LookupSuggest.suggest("t:1h30", lists).map { it.text }
        assertTrue("t:1h30m" in continued)
        assertFalse(continued.any { it == "t:today" })

        val after = LookupSuggest.suggest("after:1", lists).map { it.text }
        assertTrue("after:1h" in after)
        assertFalse("after:today" in after)
        assertTrue("t:today" in LookupSuggest.suggest("t:to", lists).map { it.text })
    }

    @Test
    fun `export import suggests snapshot files`() {
        val directory = Files.createTempDirectory("tracel-suggest")
        try {
            Files.writeString(directory.resolve("world.tracel"), "x")
            Files.writeString(directory.resolve("notes.txt"), "nope")
            val files = ExportSuggest.files(directory, "")
            assertEquals(listOf("world.tracel"), files.map { it.text })
            assertTrue(files.single().tooltip!!.contains("KiB"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
