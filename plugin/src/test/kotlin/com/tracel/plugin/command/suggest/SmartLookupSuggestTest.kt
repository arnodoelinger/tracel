package com.tracel.plugin.command.suggest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartLookupSuggestTest {

    private val onlinePlayers = listOf("Alice", "Bob", "Charlie")
    private val worldNames = listOf("world", "world_nether", "world_the_end")
    private val actionNames = listOf("block", "container", "kill", "craft")
    private val itemNames = listOf("diamond", "diamond_sword", "stick", "stone")
    private val blockNames = listOf("stone", "sand", "dirt")

    @Test
    fun `suggests all primary flags with tooltips when input is empty`() {
        val suggestions = LookupSuggest.computeSuggestions(
            currentToken = "",
            previousTokens = emptyList(),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
            includeUndo = true,
        )

        val flags = suggestions.map { it.text }
        assertTrue("u:" in flags)
        assertTrue("t:" in flags)
        assertTrue("#preview" in flags)
        assertTrue("#blocks" in flags)
        assertTrue("#items" in flags)
        assertTrue("undo" in flags)

        val previewSuggestion = suggestions.first { it.text == "#preview" }
        assertNotNull(previewSuggestion.tooltip)
        assertTrue(previewSuggestion.tooltip!!.contains("Preview"))
    }

    @Test
    fun `deduplicates flags already typed in previous tokens`() {
        val suggestions = LookupSuggest.computeSuggestions(
            currentToken = "",
            previousTokens = listOf("u:Alice", "t:1h", "#preview"),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )

        val flags = suggestions.map { it.text }
        assertFalse("u:" in flags, "u: should be suppressed since it was already specified")
        assertFalse("user:" in flags, "user: should be suppressed since u: was already specified")
        assertFalse("t:" in flags, "t: should be suppressed since it was already specified")
        assertFalse("time:" in flags, "time: should be suppressed since t: was already specified")
        assertFalse("#preview" in flags, "#preview should be suppressed since it was already specified")

        assertTrue("i:" in flags)
        assertTrue("scope:" in flags)
        assertTrue("#blocks" in flags)
    }

    @Test
    fun `mutually exclusive flags blocks and items hide each other`() {
        val suggestionsWithBlocks = LookupSuggest.computeSuggestions(
            currentToken = "",
            previousTokens = listOf("#blocks"),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )
        val flagsWithBlocks = suggestionsWithBlocks.map { it.text }
        assertFalse("#blocks" in flagsWithBlocks)
        assertFalse("#items" in flagsWithBlocks, "#items must be hidden when #blocks is used")

        val suggestionsWithItems = LookupSuggest.computeSuggestions(
            currentToken = "",
            previousTokens = listOf("#items"),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )
        val flagsWithItems = suggestionsWithItems.map { it.text }
        assertFalse("#items" in flagsWithItems)
        assertFalse("#blocks" in flagsWithItems, "#blocks must be hidden when #items is used")
    }

    @Test
    fun `comma-separated player suggestions do not repeat already selected players`() {
        val suggestions = LookupSuggest.computeSuggestions(
            currentToken = "u:Alice,",
            previousTokens = emptyList(),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )

        val texts = suggestions.map { it.text }
        assertEquals(listOf("u:Alice,Bob", "u:Alice,Charlie"), texts)
        assertFalse("u:Alice,Alice" in texts, "Alice should not be suggested again")
    }

    @Test
    fun `comma-separated action suggestions filter matching remainder`() {
        val suggestions = LookupSuggest.computeSuggestions(
            currentToken = "a:block,c",
            previousTokens = emptyList(),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )

        val texts = suggestions.map { it.text }
        assertEquals(listOf("a:block,container", "a:block,craft"), texts)
    }

    @Test
    fun `partial prefix matches suggest relevant values with descriptions`() {
        val timeSuggestions = LookupSuggest.computeSuggestions(
            currentToken = "t:1",
            previousTokens = emptyList(),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )
        val timeTexts = timeSuggestions.map { it.text }
        assertTrue("t:1h" in timeTexts)
        assertTrue("t:1d" in timeTexts)
        assertTrue("t:15m" in timeTexts)

        val itemSuggestions = LookupSuggest.computeSuggestions(
            currentToken = "i:dia",
            previousTokens = emptyList(),
            onlinePlayers = onlinePlayers,
            worldNames = worldNames,
            actionNames = actionNames,
            itemNames = itemNames,
            blockNames = blockNames,
        )
        val itemTexts = itemSuggestions.map { it.text }
        assertEquals(listOf("i:diamond", "i:diamond_sword"), itemTexts)
    }
}
