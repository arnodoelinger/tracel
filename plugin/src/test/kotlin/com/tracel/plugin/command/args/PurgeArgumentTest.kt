package com.tracel.plugin.command.args

import com.tracel.engine.store.PurgeCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PurgeArgumentTest {
    private fun parse(line: String) = PurgeArgument.parse(line.split(' ').filter { it.isNotBlank() })

    @Test
    fun `nothing, or only a confirm, is a request for help and never for a wipe`() {
        assertTrue(parse("").isEmpty)
        assertTrue(parse("#confirm").isEmpty)
        assertTrue(parse("#confirm").confirmed)
    }

    @Test
    fun `filters mix in any order`() {
        val args = parse("world world_nether older 30d category blocks,items player Steve")

        assertEquals(setOf(PurgeCategory.BLOCKS, PurgeCategory.ITEMS), args.categories)
        assertEquals(30L * 86_400_000, args.olderMillis)
        assertEquals("world_nether", args.world)
        assertEquals("Steve", args.player)
        assertEquals(emptyList<Any>(), args.errors)
        assertFalse(args.confirmed)
    }

    @Test
    fun `a confirm can come last, and keywords are not case sensitive`() {
        val args = parse("OLDER 12h #confirm")

        assertEquals(12L * 3_600_000, args.olderMillis)
        assertTrue(args.confirmed)
        assertEquals(emptyList<Any>(), args.errors)
    }

    @Test
    fun `all is alone`() {
        assertTrue(parse("all").everything)
        assertEquals(emptyList<Any>(), parse("all #confirm").errors)
        assertEquals(1, parse("all older 1d").errors.size, "a wipe with a filter is a mistake, not a wipe")
    }

    @Test
    fun `a flag with no value, or with another flag where the value goes, is an error`() {
        assertEquals(1, parse("older").errors.size)
        assertEquals(1, parse("older world nether").errors.size)
        assertEquals(1, parse("world #confirm").errors.size)
        assertEquals("nether", parse("older world nether").world, "the word that was taken for a value is still read")
    }

    @Test
    fun `a bad span, a zero span and an unknown category are errors`() {
        assertEquals(1, parse("older soon").errors.size)
        assertEquals(1, parse("older 0d").errors.size)
        assertNull(parse("older 0d").olderMillis)
        assertEquals(1, parse("category blocks,lots").errors.size)
        assertEquals(setOf(PurgeCategory.BLOCKS), parse("category blocks,lots").categories)
    }

    @Test
    fun `a flag given twice, and a stray word, are errors`() {
        assertEquals(1, parse("older 1d older 2d").errors.size)
        assertEquals(1, parse("older 1d please").errors.size)
    }
}
