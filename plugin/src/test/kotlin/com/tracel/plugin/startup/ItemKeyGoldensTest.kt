package com.tracel.plugin.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ItemKeyGoldensTest {
    @Test
    fun `parseGoldens reads name-equals-hex lines, skipping blanks and comments`() {
        val text = """
            # a comment
            enchanted sword=abc123

            custom name = def456
        """.trimIndent()

        assertEquals(
            listOf(Golden("enchanted sword", "abc123"), Golden("custom name", "def456")),
            parseGoldens(text),
        )
    }

    @Test
    fun `a matching hash is reported as Match`() {
        val goldens = listOf(Golden("sword", "abc123"))
        val result = checkGoldens(goldens, mapOf("sword" to "abc123")).single()
        assertInstanceOf(GoldenResult.Match::class.java, result)
    }

    @Test
    fun `a changed hash is reported as Drifted with both values`() {
        val goldens = listOf(Golden("sword", "abc123"))
        val result = checkGoldens(goldens, mapOf("sword" to "different")).single() as GoldenResult.Drifted
        assertEquals("abc123", result.expectedHex)
        assertEquals("different", result.actualHex)
    }

    @Test
    fun `an item with no checked-in golden yet is Unrecorded, not a failure`() {
        val result = checkGoldens(emptyList(), mapOf("new item" to "abc123")).single()
        assertInstanceOf(GoldenResult.Unrecorded::class.java, result)
    }
}
