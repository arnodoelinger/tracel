package com.tracel.plugin.filters

import com.tracel.annotations.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.plugin.command.args.ACTION_NAMES
import com.tracel.plugin.command.args.LookupScope
import com.tracel.plugin.command.args.parseActionFilter
import com.tracel.plugin.command.args.parseLookupArgs
import com.tracel.plugin.command.args.suggestLookupToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val NOW = 1_756_000_000_000L

class LookupFlagsTest {
    @Test
    fun `blocks and items are the same two flags under the words people reach for`() {
        assertTrue(parseLookupArgs(listOf("#blocks"), NOW).structureOnly)
        assertFalse(parseLookupArgs(listOf("#blocks"), NOW).materialOnly)
        assertTrue(parseLookupArgs(listOf("#items"), NOW).materialOnly)
        assertFalse(parseLookupArgs(listOf("#items"), NOW).structureOnly)
    }

    @Test
    fun `explosion is a hash flag like blocks and items`() {
        assertEquals(setOf("explosion"), parseLookupArgs(listOf("#explosion"), NOW).actions)
        assertEquals(
            parseLookupArgs(listOf("a:explosion"), NOW).actions,
            parseLookupArgs(listOf("#explosion"), NOW).actions,
        )
    }

    @Test
    fun `a world and a radius are different fields, in either order`() {
        val worldFirst = parseLookupArgs(listOf("w:nether", "scope:20b"), NOW)
        val radiusFirst = parseLookupArgs(listOf("scope:20b", "w:nether"), NOW)

        assertEquals(LookupScope.Blocks(20), worldFirst.scope)
        assertEquals("nether", worldFirst.world)
        assertEquals(worldFirst.scope, radiusFirst.scope)
        assertEquals(worldFirst.world, radiusFirst.world)
    }

    @Test
    fun `scope naming a world lands in the world field, not the radius`() {
        val parsed = parseLookupArgs(listOf("scope:the_end"), NOW)
        assertNull(parsed.scope)
        assertEquals("the_end", parsed.world)
    }

    @Test
    fun `an oversized radius is still a radius, so the command layer can refuse it`() {
        val parsed = parseLookupArgs(listOf("scope:99999b"), NOW)
        assertEquals(LookupScope.Blocks(99999), parsed.scope)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `an unparseable flag value is an error, not silence`() {
        assertTrue(parseLookupArgs(listOf("l:many"), NOW).errors.isNotEmpty())
        assertTrue(parseLookupArgs(listOf("nonsense"), NOW).errors.isNotEmpty())
        assertTrue(parseLookupArgs(listOf("limit:5"), NOW).errors.isNotEmpty())
        assertTrue(parseLookupArgs(listOf("offset:5"), NOW).errors.isNotEmpty())
        assertTrue(parseLookupArgs(listOf("#structure"), NOW).errors.isNotEmpty())
        assertTrue(parseLookupArgs(listOf("#count"), NOW).errors.isNotEmpty())
    }

    @Test
    fun `block and item flags land in the same material field`() {
        assertEquals("stone", parseLookupArgs(listOf("b:stone"), NOW).item)
        assertEquals("stone", parseLookupArgs(listOf("block:stone"), NOW).item)
        assertEquals("cookie", parseLookupArgs(listOf("i:cookie"), NOW).item)
        assertEquals("cookie", parseLookupArgs(listOf("item:cookie"), NOW).item)
    }

    @Test
    fun `tab complete offers values after a prefix, not just the prefix itself`() {
        val names = suggestLookupToken(
            "i:st",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ACTION_NAMES,
            itemNames = listOf("stick", "stone", "string"),
            blockNames = listOf("stone", "sand"),
        )
        assertEquals(listOf("i:stick", "i:stone", "i:string"), names)

        val blocks = suggestLookupToken(
            "b:s",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ACTION_NAMES,
            itemNames = listOf("stick"),
            blockNames = listOf("stone", "sand"),
        )
        assertEquals(listOf("b:stone", "b:sand"), blocks)

        val times = suggestLookupToken(
            "t:1",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ACTION_NAMES,
        )
        assertTrue("t:1h" in times)
        assertTrue("t:1d" in times)

        val actions = suggestLookupToken(
            "a:b",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ACTION_NAMES,
        )
        assertTrue("a:block" in actions)
    }
}

class ActionFilterTest {
    @Test
    fun `no action filter selects both halves`() {
        val filter = parseActionFilter(emptySet())
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `a block filter leaves the ledger alone`() {
        val filter = parseActionFilter(setOf("block"))
        assertTrue(filter.structural)
        assertFalse(filter.material)
        assertEquals(
            setOf(ActionKind.BLOCK_PLACE, ActionKind.BLOCK_BREAK, ActionKind.BLOCK_CHANGE),
            filter.actions,
        )
    }

    @Test
    fun `a container filter leaves the world alone`() {
        val filter = parseActionFilter(setOf("container"))
        assertTrue(filter.material)
        assertFalse(filter.structural)
        assertTrue(CauseKind.PLAYER_ACTION in filter.causes)
    }

    @Test
    fun `an explosion is both a crater and the chests in it`() {
        val filter = parseActionFilter(setOf("explosion"))
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `naming one of each keeps both`() {
        val filter = parseActionFilter(setOf("block", "container"))
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `an unknown action is reported and changes neither half`() {
        val filter = parseActionFilter(setOf("wat"))
        assertEquals(listOf("wat"), filter.unknown)
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `a raw cause name narrows both logs`() {
        val filter = parseActionFilter(setOf("hopper"))
        assertTrue(CauseKind.HOPPER in filter.causes)
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }
}
