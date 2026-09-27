package com.tracel.plugin.util.command

import com.tracel.plugin.util.CommandOrigin
import com.tracel.plugin.util.ParsedBlockPos
import com.tracel.plugin.util.parseBlockPos
import com.tracel.plugin.util.tokenize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandCoordinateParserTest {
    private val origin = CommandOrigin(10.4, 64.0, -20.6)

    @Test
    fun `absolute coordinates need no origin at all`() {
        assertEquals(ParsedBlockPos(1, 2, 3), parseBlockPos(listOf("1", "2", "3"), 0, origin = null))
    }

    @Test
    fun `bare tilde resolves to the origin, floored`() {
        assertEquals(ParsedBlockPos(10, 64, -21), parseBlockPos(listOf("~", "~", "~"), 0, origin))
    }

    @Test
    fun `tilde with an offset adds to the origin before flooring`() {
        assertEquals(ParsedBlockPos(15, 68, -26), parseBlockPos(listOf("~5", "~4", "~-5"), 0, origin))
    }

    @Test
    fun `mixed absolute and relative axes resolve independently`() {
        // z: -20.6 + 2.4 = -18.2, floors to -19.
        assertEquals(ParsedBlockPos(100, 64, -19), parseBlockPos(listOf("100", "~", "~2.4"), 0, origin))
    }

    @Test
    fun `a relative coordinate with no origin does not resolve`() {
        assertNull(parseBlockPos(listOf("~", "~", "~"), 0, origin = null))
    }

    @Test
    fun `a local coordinate never resolves, origin or not`() {
        assertNull(parseBlockPos(listOf("^", "^", "^1"), 0, origin))
        assertNull(parseBlockPos(listOf("^", "^", "^1"), 0, origin = null))
    }

    @Test
    fun `garbage numbers do not resolve`() {
        assertNull(parseBlockPos(listOf("abc", "2", "3"), 0, origin))
    }

    @Test
    fun `too few tokens left in the list does not resolve`() {
        assertNull(parseBlockPos(listOf("1", "2"), 0, origin))
    }

    @Test
    fun `reads three tokens starting at the given index, not just the front of the list`() {
        val tokens = listOf("1", "2", "3", "4", "5", "6")
        assertEquals(ParsedBlockPos(4, 5, 6), parseBlockPos(tokens, 3, origin = null))
    }

    @Test
    fun `tokenize stops at the cap and leaves the rest as one tail`() {
        val body = "1 2 3 minecraft:chest[facing=north]{CustomName:'\"a b\"'}"
        val tokens = tokenize(body, 3)
        assertEquals(listOf("1", "2", "3", "minecraft:chest[facing=north]{CustomName:'\"a b\"'}"), tokens)
    }

    @Test
    fun `tokenize on an empty body returns nothing`() {
        assertEquals(emptyList<String>(), tokenize("   ", 3))
    }
}
