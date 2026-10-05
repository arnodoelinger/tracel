package com.tracel.plugin.filters

import com.tracel.model.transaction.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.plugin.command.args.ActionArgument
import com.tracel.plugin.command.args.LookupScope
import com.tracel.plugin.command.args.parseLookupArgs
import com.tracel.plugin.command.args.suggestLookupToken
import com.tracel.plugin.i18n.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

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
    fun `s is scope, short`() {
        assertEquals(LookupScope.Blocks(20), parseLookupArgs(listOf("s:20b"), NOW).scope)
        assertEquals(LookupScope.CurrentBlock, parseLookupArgs(listOf("s:block"), NOW).scope)
        assertEquals("world_nether", parseLookupArgs(listOf("s:world_nether"), NOW).world)
        assertEquals(
            0, com.tracel.plugin.command.args.RollbackArgument.missingBounds(
                parseLookupArgs(listOf("t:10m", "s:2c"), NOW)
            ).size
        )
    }

    @Test
    fun `scope block is the block you stand on, not a world`() {
        val parsed = parseLookupArgs(listOf("scope:block"), NOW)
        assertEquals(LookupScope.CurrentBlock, parsed.scope)
        assertEquals(null, parsed.world)
    }

    @Test
    fun `a rollback needs both a time and a scope`() {
        fun missing(vararg flags: String) =
            com.tracel.plugin.command.args.RollbackArgument.missingBounds(parseLookupArgs(flags.toList(), NOW))

        assertEquals(2, missing().size)
        assertEquals(1, missing("t:10m").size)
        assertEquals(1, missing("scope:20b").size)
        assertEquals(1, missing("t:10m", "scope:world_nether").size, "a world is not a scope")
        assertEquals(0, missing("t:10m", "scope:block").size)
        assertEquals(0, missing("time:1h", "scope:2c", "u:Alice").size)
    }

    @Test
    fun `a bare token is an error, every value has its flag`() {
        for (bare in listOf("10m", "20b", "2c", "block", "Steve", "kill", "10")) {
            assertTrue(parseLookupArgs(listOf(bare), NOW).errors.isNotEmpty(), "$bare should not parse on its own")
        }
        val parsed = parseLookupArgs(listOf("t:10m", "s:20b", "u:Steve", "a:kill"), NOW)
        assertEquals(NOW - 600_000, parsed.since)
        assertEquals(LookupScope.Blocks(20), parsed.scope)
        assertEquals(setOf("Steve"), parsed.users)
        assertEquals(setOf("kill"), parsed.actions)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `a misspelled flag is an invalid argument`() {
        val hint = parseLookupArgs(listOf("scop:20b"), NOW).errors.single().plain()
        assertTrue("scop:20b" in hint, hint)
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
        assertTrue(parseLookupArgs(listOf("no!nsense"), NOW).errors.isNotEmpty())
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
            causeNames = ActionArgument.NAMES,
            itemNames = listOf("stick", "stone", "string"),
            blockNames = listOf("stone", "sand"),
        )
        assertEquals(listOf("i:stick", "i:stone", "i:string"), names)

        val blocks = suggestLookupToken(
            "b:s",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ActionArgument.NAMES,
            itemNames = listOf("stick"),
            blockNames = listOf("stone", "sand"),
        )
        assertEquals(listOf("b:sand", "b:stone"), blocks)

        val times = suggestLookupToken(
            "t:1",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ActionArgument.NAMES,
        )
        assertTrue("t:1h" in times)
        assertTrue("t:1d" in times)

        val actions = suggestLookupToken(
            "a:b",
            onlinePlayerNames = emptyList(),
            worldNames = emptyList(),
            causeNames = ActionArgument.NAMES,
        )
        assertTrue("a:block" in actions)
    }
}

class ActionFilterTest {
    @Test
    fun `no action filter selects both halves`() {
        val filter = ActionArgument.parse(emptySet())
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `a block filter leaves the ledger alone`() {
        val filter = ActionArgument.parse(setOf("block"))
        assertTrue(filter.structural)
        assertFalse(filter.material)
        assertEquals(
            setOf(ActionKind.BLOCK_PLACE, ActionKind.BLOCK_BREAK, ActionKind.BLOCK_CHANGE, ActionKind.BLOCK_GROW),
            filter.actions,
        )
    }

    @Test
    fun `a container filter leaves the world alone`() {
        val filter = ActionArgument.parse(setOf("container"))
        assertTrue(filter.material)
        assertFalse(filter.structural)
        assertTrue(CauseKind.PLAYER_ACTION in filter.causes)
    }

    @Test
    fun `an explosion is both a crater and the chests in it`() {
        val filter = ActionArgument.parse(setOf("explosion"))
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `naming one of each keeps both`() {
        val filter = ActionArgument.parse(setOf("block", "container"))
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `an unknown action is reported and changes neither half`() {
        val filter = ActionArgument.parse(setOf("wat"))
        assertEquals(listOf("wat"), filter.unknown)
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }

    @Test
    fun `a raw cause name narrows both logs`() {
        val filter = ActionArgument.parse(setOf("hopper"))
        assertTrue(CauseKind.HOPPER in filter.causes)
        assertTrue(filter.structural)
        assertTrue(filter.material)
    }
}

private fun Component.plain(locale: Locale = Locale.ENGLISH): String {
    Messages.install(Messages.LOCALES.associateWith {
        val file = Messages::class.java.classLoader.getResourceAsStream("lang/${it.language}.json")
        Messages.read(checkNotNull(file)).mapKeys { key -> Messages.PREFIX + key.key }
    })
    return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(this, locale))
}
