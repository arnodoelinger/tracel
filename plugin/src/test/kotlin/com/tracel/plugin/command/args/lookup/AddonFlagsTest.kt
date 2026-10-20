package com.tracel.plugin.command.args.lookup

import com.tracel.api.addon.Addon
import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.QueryPart
import com.tracel.api.addon.TracelAddon
import com.tracel.api.addon.flag
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import com.tracel.plugin.api.addon.AddonManager
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.command.args.action.ActionFilter
import com.tracel.plugin.command.args.rollback.RollbackArgument
import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.SuggestLists
import com.tracel.plugin.command.suggest.addonFlags
import com.tracel.plugin.command.suggest.flag.FlagProfile
import com.tracel.plugin.command.suggest.flag.FlagSuggest
import java.util.UUID
import java.util.logging.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddonFlagsTest {
    private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
    private val manager = AddonManager(logger, isPluginEnabled = { true }, extensions = Extensions.live)
    private val world = UUID(0, 7)
    private val spawn = LookupArea.Around(BlockPlace(world, 0, 64, 0), 32)
    private val now = 1_000_000L

    @Addon(id = "regions")
    class Regions(private val area: LookupArea) : TracelAddon {
        lateinit var context: AddonContext

        override fun onEnable(context: AddonContext) {
            this.context = context
            context.flags.register(
                flag("region") {
                    description = "A region"
                    parse { input ->
                        if (input.value == "spawn") Res.Ok(QueryPart.within(area)) else Res.Err("no region of that name")
                    }
                    suggest { listOf("spawn", "arena", "market") }
                },
            )
        }
    }

    @BeforeEach
    fun reserve() {
        Extensions.live.reservedFlags = builtInFlags()
    }

    @AfterEach
    fun stop() {
        manager.stop()
        Extensions.live.reservedFlags = emptySet()
    }

    private fun start(): Regions = Regions(spawn).also {
        manager.register(it)
        manager.start()
    }

    @Test
    fun `a flag an addon added turns into a part of the query`() {
        start()

        val parsed = parseLookupArgs(listOf("region:spawn", "t:10m"), now)

        assertTrue(parsed.errors.isEmpty())
        val query = parsed.lookupQuery(emptyList(), ActionFilter(), area = null)
        assertEquals(spawn, query.area)
    }

    @Test
    fun `an area flag stands in for scope when a rollback needs one`() {
        start()

        assertTrue(RollbackArgument.missingBounds(parseLookupArgs(listOf("t:10m", "region:spawn"), now)).isEmpty())
        assertEquals(1, RollbackArgument.missingBounds(parseLookupArgs(listOf("t:10m"), now)).size)
    }

    @Test
    fun `a value the flag refuses is an error, and nothing narrows`() {
        start()

        val parsed = parseLookupArgs(listOf("region:nowhere"), now)

        assertEquals(1, parsed.errors.size)
        assertTrue(parsed.parts.isEmpty())
    }

    @Test
    fun `without the addon the token is not a flag`() {
        assertEquals(1, parseLookupArgs(listOf("region:spawn"), now).errors.size)

        start()
        manager.stop()

        assertEquals(1, parseLookupArgs(listOf("region:spawn"), now).errors.size)
    }

    @Test
    fun `tab offers the flag, then what may follow it`() {
        start()
        val lists = SuggestLists(addonFlags = addonFlags(null))

        val names = FlagSuggest.complete(FlagProfile.LOOKUP, "reg", emptyList(), lists)
        val values = FlagSuggest.complete(FlagProfile.LOOKUP, "region:a", emptyList(), lists)

        assertEquals(listOf("region:"), names.map(Suggestion::text))
        assertEquals(setOf("region:arena", "region:market", "region:spawn"), values.map(Suggestion::text).toSet())
    }

    @Test
    fun `a name that is Tracel's own, malformed or taken cannot be registered`() {
        val addon = start()

        for (bad in listOf("t", "scope", "Region", "two words", "")) {
            assertThrows(IllegalArgumentException::class.java, { addon.context.flags.register(flag(bad) {}) }, bad)
        }
        assertThrows(IllegalArgumentException::class.java) { addon.context.flags.register(flag("region") {}) }
    }
}
