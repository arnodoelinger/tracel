package com.tracel.plugin.command.suggest

import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.tracel.plugin.i18n.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Locale

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

        assertFalse("undo" in flags, "taking a rollback back is /tracel undo")
        assertTrue("u:" in flags)
        assertTrue("t:" in flags)
        assertTrue("#preview" in flags)
        assertTrue("#blocks" in flags)
        assertTrue("#items" in flags)
        assertFalse("user:" in flags)

        val preview = suggestions.first { it.text == "#preview" }
        assertTrue(preview.tooltip!!.plain().contains("Preview"))
    }

    @Test
    fun `lookup does not offer rollback-only flags`() {
        val flags = LookupSuggest.suggest("", lists).map { it.text }
        assertTrue("u:" in flags)
        assertTrue("s:" in flags)
        assertFalse("scope:" in flags, "the long form is for whoever types it out")
        assertTrue("#blocks" in flags)
        assertFalse("undo" in flags)
        assertFalse("#preview" in flags)
        assertFalse("#strict" in flags)
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
        assertTrue("s:" in flags)
        assertFalse("scope:" in flags, "the long form is for whoever types it out")
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
        assertEquals("Crafting", LookupSuggest.suggest("a:block,c", lists).first { it.text.endsWith("craft") }.tooltip?.plain())
    }

    @Test
    fun `a partial time or item narrows the list`() {
        val times = LookupSuggest.suggest("t:1", lists).map { it.text }
        assertTrue("t:1h" in times)
        assertTrue("t:1d" in times)
        assertTrue("t:10m" in times)
        assertFalse("t:12" in times, "a bare number is never a suggestion")

        val items = LookupSuggest.suggest("i:dia", lists).map { it.text }
        assertEquals(listOf("i:diamond", "i:diamond_sword"), items)
    }

    @Test
    fun `any radius offers blocks and chunks after the digits`() {
        val open = LookupSuggest.suggest("scope:100", lists)
        assertEquals(listOf("scope:100b"), open.map { it.text }, "100 chunks is past the 64-chunk limit")

        val blocks = LookupSuggest.suggest("scope:100b", lists).map { it.text }
        assertEquals(listOf("scope:100b"), blocks)

        val chunks = LookupSuggest.suggest("scope:42c", lists).map { it.text }
        assertEquals(listOf("scope:42c"), chunks)

        val odd = LookupSuggest.suggest("scope:42", lists).map { it.text }
        assertTrue("scope:42b" in odd)
        assertTrue("scope:42c" in odd)
    }

    @Test
    fun `a single digit radius offers units, not more digits`() {
        val four = LookupSuggest.suggest("scope:4", lists).map { it.text }
        assertTrue("scope:4b" in four)
        assertTrue("scope:4c" in four)
        assertTrue("scope:4c" in four)
        assertTrue(four.none { it.length == "scope:4".length + 1 && it.last().isDigit() })

        val longer = LookupSuggest.suggest("scope:40", lists).map { it.text }
        assertEquals(listOf("scope:40b", "scope:40c"), longer)
    }

    @Test
    fun `an empty scope offers presets in size order and never a bare number`() {
        val texts = LookupSuggest.suggest("scope:", lists).map { it.text }
        assertEquals(
            listOf(
                "scope:4b", "scope:8b", "scope:16b", "scope:32b", "scope:64b", "scope:128b",
                "scope:1c", "scope:2c", "scope:4c", "scope:8c",
                "scope:block", "scope:chunk", "scope:world", "scope:world_nether", "scope:world_the_end",
            ),
            texts,
        )
        assertEquals("4 blocks around you", LookupSuggest.suggest("scope:", lists).first().tooltip?.plain())
        assertEquals(listOf("scope:block"), LookupSuggest.suggest("scope:bl", lists).map { it.text })
    }

    @Test
    fun `a radius over the limit is not offered`() {
        val huge = LookupSuggest.suggest("scope:5000", lists).map { it.text }
        assertTrue(huge.isEmpty(), "past 1024 blocks and 64 chunks there is nothing to offer")

        val chunks = LookupSuggest.suggest("scope:500", lists).map { it.text }
        assertEquals(listOf("scope:500b"), chunks)
    }

    @Test
    fun `a typed radius completes to the whole token`() {
        val full = "/tracel lookup scope:4"
        val builder = SuggestionsBuilder(full, "/tracel lookup ".length)
        val done = builder.reply(LookupSuggest.suggest("scope:4", lists)).join()

        val applied = done.list.associate { it.text to it.apply(full) }
        assertEquals("${full}b", applied["scope:4b"])
        assertTrue(done.list.first { it.text == "scope:4b" }.tooltip.string.contains("block"))
        assertTrue(done.list.first { it.text == "scope:4c" }.tooltip.string.contains("chunk"))
    }

    @Test
    fun `suggestions keep the order they were given`() {
        val full = "/tracel lookup scope:"
        val builder = SuggestionsBuilder(full, "/tracel lookup ".length)
        val done = builder.reply(LookupSuggest.suggest("scope:", lists)).join()
        val texts = done.list.map { it.text }
        assertTrue(texts.indexOf("scope:4b") < texts.indexOf("scope:16b"))
        assertTrue(texts.indexOf("scope:16b") < texts.indexOf("scope:128b"))
    }

    @Test
    fun `a time value offers a unit for whatever number was typed`() {
        val fortyFive = LookupSuggest.suggest("t:45", lists).map { it.text }
        assertTrue("t:45s" in fortyFive)
        assertTrue("t:45m" in fortyFive)
        assertTrue("t:45h" in fortyFive)
        assertTrue("t:45d" in fortyFive)
        assertTrue("t:45w" in fortyFive)
        assertFalse("t:450" in fortyFive)

        val four = LookupSuggest.suggest("t:4", lists).map { it.text }
        assertTrue("t:4h" in four)
        assertTrue("t:4d" in four)
        assertTrue(four.none { it.length == "t:4".length + 1 && it.last().isDigit() })

        val continued = LookupSuggest.suggest("t:1h30", lists).map { it.text }
        assertTrue("t:1h30m" in continued)
        assertTrue("t:1h30s" in continued)
        assertFalse("t:1h300" in continued)
        assertFalse(continued.any { it == "t:today" })

        val openHour = LookupSuggest.suggest("t:1h3", lists).map { it.text }
        assertTrue("t:1h3m" in openHour)
        assertFalse("t:1h30" in openHour)

        val after = LookupSuggest.suggest("after:1", lists).map { it.text }
        assertTrue("after:1h" in after)
        assertFalse("after:today" in after)
        assertTrue("t:today" in LookupSuggest.suggest("t:to", lists).map { it.text })
    }

    @Test
    fun `an empty time offers windows in size order, described as the past`() {
        val list = LookupSuggest.suggest("t:", lists)
        val texts = list.map { it.text }
        assertEquals("t:10s", texts.first())
        assertTrue(texts.indexOf("t:1m") < texts.indexOf("t:10m"))
        assertTrue(texts.indexOf("t:10m") < texts.indexOf("t:1h"))
        assertTrue("t:today" in texts)
        assertEquals("Past 10 seconds", list.first().tooltip?.plain())
        assertEquals("Past 1 minute", list.first { it.text == "t:1m" }.tooltip?.plain())
        assertTrue(texts.none { it.length == 3 && it.last().isDigit() })
    }

    @Test
    fun `a whole typed duration says what it means`() {
        val list = LookupSuggest.suggest("t:1h30m", lists)
        assertEquals(listOf("t:1h30m"), list.map { it.text })
        assertEquals("Past 1 hour 30 minutes", list.single().tooltip?.plain())
        assertEquals("2 days ago", LookupSuggest.suggest("after:2d", lists).first { it.text == "after:2d" }.tooltip?.plain())
    }

    @Test
    fun `a bare number offers a time and a scope, a bare name offers players and words`() {
        val digits = LookupSuggest.suggest("10", lists).map { it.text }
        assertTrue("10m" in digits && "10h" in digits, "10 as a time")
        assertTrue("10b" in digits && "10c" in digits, "10 as a scope")

        val timeTaken = LookupSuggest.suggest("t:1h 10", lists).map { it.text }
        assertTrue("10m" !in timeTaken && "10b" in timeTaken)

        val names = LookupSuggest.suggest("Al", lists).map { it.text }
        assertEquals("Alice", names.first { it.startsWith("Al") })
        assertTrue(LookupSuggest.suggest("-al", lists).isEmpty(), "there is no exclusion by name")
        assertTrue("world_nether" in LookupSuggest.suggest("nether", lists).map { it.text })
        assertTrue("chunk" in LookupSuggest.suggest("ch", lists).map { it.text })
        assertTrue(LookupSuggest.suggest("", lists).none { it.text == "Alice" }, "nothing bare until something is typed")
    }

    @Test
    fun `export import suggests snapshot files`() {
        val directory = Files.createTempDirectory("tracel-suggest")
        try {
            Files.writeString(directory.resolve("world.tracel"), "x")
            Files.writeString(directory.resolve("notes.txt"), "nope")
            val files = ExportSuggest.files(directory, "")
            assertEquals(listOf("world.tracel"), files.map { it.text })
            assertTrue(files.single().tooltip!!.plain().contains("KiB"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scope is s in the first suggestions and scope when typed out`() {
        assertTrue(LookupSuggest.suggest("", lists).any { it.text == "s:" })
        assertEquals(listOf("scope:"), LookupSuggest.suggest("sco", lists).map { it.text })
        val both = LookupSuggest.suggest("s", lists).map { it.text }
        assertTrue("s:" in both && "scope:" in both)
        assertEquals(listOf("s:20b", "s:20c"), LookupSuggest.suggest("s:20", lists).map { it.text })
    }
}

private fun Component.plain(locale: Locale = Locale.ENGLISH): String {
    Messages.install(Messages.LOCALES.associateWith {
        val file = Messages::class.java.classLoader.getResourceAsStream("lang/${it.language}.json")
        Messages.read(checkNotNull(file)).mapKeys { key -> Messages.PREFIX + key.key }
    })
    return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(this, locale))
}
