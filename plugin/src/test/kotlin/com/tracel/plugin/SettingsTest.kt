package com.tracel.plugin

import com.tracel.engine.store.StoreSync
import com.tracel.engine.store.StoreSettings
import com.tracel.engine.store.PurgeCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tomlj.Toml

class SettingsTest {
    private val complaints = mutableListOf<String>()

    private fun read(vararg pairs: Pair<String, Any?>): Settings {
        val body = pairs.joinToString("\n") { (key, value) -> "$key = ${toml(value)}" }
        return readSettings(Toml.parse(body), complain = complaints::add)
    }

    private fun readRollback(vararg pairs: Pair<String, Any?>): Settings {
        val body = pairs.joinToString("\n") { (key, value) -> "$key = ${toml(value)}" }
        return readSettings(advanced = null, rollback = Toml.parse(body), complain = complaints::add)
    }

    private fun toml(value: Any?): String = when (value) {
        is String -> "\"$value\""
        else -> value.toString()
    }

    @Test
    fun `max tick time is read in milliseconds and a nonsense value keeps the default`() {
        assertEquals(10_000_000L, readRollback("max-tick-time" to "10ms").governor.maxNanos)
        assertTrue(complaints.isEmpty())

        assertEquals(35_000_000L, readRollback("max-tick-time" to "1ms").governor.maxNanos)
        assertEquals(35_000_000L, readRollback("max-tick-time" to "80ms").governor.maxNanos)
        assertEquals(2, complaints.size)
    }

    @Test
    fun `min tick time is read in milliseconds and may not be longer than the maximum`() {
        assertEquals(8_000_000L, readRollback("min-tick-time" to "8ms").governor.minNanos)
        assertTrue(complaints.isEmpty())

        val both = readRollback("min-tick-time" to "12ms", "max-tick-time" to "6ms")
        assertEquals(12_000_000L, both.governor.minNanos)
        assertEquals(35_000_000L, both.governor.maxNanos)
        assertEquals(1, complaints.size)
    }

    @Test
    fun `the shipped config toml is exactly the defaults, written out`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/config.toml")) { "config.toml is not on the classpath" }
            .reader().readText()
        val config = Toml.parse(text)

        assertEquals(
            Settings(),
            readSettings(
                config.getTable("advanced"),
                config.getTable("rollback"),
                complaints::add,
                config.getTable("paste"),
                config.getTable("purge")
            )
        )
        assertTrue(complaints.isEmpty()) { complaints.toString() }
    }

    @Test
    fun `an empty section is every default`() {
        assertEquals(Settings(), read())
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `sync takes the two words and any interval`() {
        assertEquals(StoreSync.EveryBatch, read("sync" to "every-batch").store.sync)
        assertEquals(StoreSync.Never, read("sync" to "NEVER").store.sync)
        assertEquals(StoreSync.Interval(500), read("sync" to "500ms").store.sync)
        assertEquals(StoreSync.Interval(5_000), read("sync" to "5s").store.sync)
        assertEquals(StoreSync.Interval(60_000), read("sync" to "1m").store.sync)
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `sizes come in the units an admin writes them in`() {
        assertEquals(16L shl 20, read("memtable-size" to "16MiB").store.memtableBytes)
        assertEquals(4L shl 20, read("memtable-size" to "4MB").store.memtableBytes)
        assertEquals(2L shl 30, read("memtable-size" to "2GiB").store.memtableBytes)
        assertEquals(8L shl 20, read("memtable-size" to 8_388_608).store.memtableBytes)
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `a value it cannot read keeps the default and says which key`() {
        val settings = read("sync" to "sometimes", "memtable-size" to "lots")

        assertEquals(StoreSettings().sync, settings.store.sync)
        assertEquals(StoreSettings().memtableBytes, settings.store.memtableBytes)
        assertEquals(2, complaints.size)
        assertTrue(complaints.any { it.contains("advanced.sync") && it.contains("sometimes") }) { complaints.toString() }
        assertTrue(complaints.any { it.contains("advanced.memtable-size") }) { complaints.toString() }
    }

    @Test
    fun `every fallback is the safe direction, so a typo costs speed and never durability`() {
        assertEquals(StoreSync.EveryBatch, read("sync" to "nevr").store.sync)
        assertEquals(StoreSync.EveryBatch, read("sync" to "every batch").store.sync)
    }

    @Test
    fun `numbers that would stall the server are refused rather than obeyed`() {
        assertEquals(StoreSettings().memtableBytes, read("memtable-size" to "4KiB").store.memtableBytes)
        assertEquals(StoreSettings().maxFrozenMemtables, read("max-pending-flushes" to 0).store.maxFrozenMemtables)
        assertEquals(2, complaints.size)
    }

    @Test
    fun `the capture ring has to be a power of two`() {
        assertEquals(1 shl 18, read("capture-ring-slots" to (1 shl 18)).store.ringSlots)
        assertEquals(StoreSettings.DEFAULT_RING_SLOTS, read("capture-ring-slots" to 65_000).store.ringSlots)
        assertEquals(StoreSettings.DEFAULT_RING_SLOTS, read("capture-ring-slots" to 512).store.ringSlots)
        assertEquals(2, complaints.size)
    }

    @Test
    fun `an entity-restore limit of zero asks about every entity`() {
        assertEquals(0, readRollback("entity-restore-limit" to 0).entityRestoreLimit)
    }

    @Test
    fun `a negative entity-restore limit is a typo, not a refusal of everything`() {
        val settings = readRollback("entity-restore-limit" to -1)

        assertEquals(DEFAULT_ENTITY_RESTORE_LIMIT, settings.entityRestoreLimit)
        assertTrue(complaints.any { it.contains("rollback.entity-restore-limit") }) { complaints.toString() }
    }

    private fun purge(body: String): Settings =
        readSettings(advanced = null, complain = complaints::add, purge = Toml.parse(body).getTable("purge"))

    @Test
    fun `the automatic purge is off until it is asked for, and keeps 90 days`() {
        val defaults = AutoPurgeSettings()

        assertEquals(false, defaults.enabled)
        assertEquals(defaults, purge("[purge]\nauto-purge = false").autoPurge)
        assertEquals(defaults, read().autoPurge)
        assertEquals(PurgeCategory.entries.toSet(), defaults.keep.keys)
        assertTrue(defaults.keep.values.all { it == 90L * 86_400_000 })
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `each category is kept for as long as it says, or forever`() {
        val settings = purge(
            """
            [purge]
            auto-purge = true
            interval = "30m"
            keep-blocks = "7D"
            keep-items = "forever"
            """.trimIndent(),
        ).autoPurge

        assertEquals(true, settings.enabled)
        assertEquals(30L * 60_000, settings.intervalMillis)
        assertEquals(7L * 86_400_000, settings.keep[PurgeCategory.BLOCKS])
        assertEquals(null, settings.keep[PurgeCategory.ITEMS], "forever is no cutoff at all")
        assertEquals(90L * 86_400_000, settings.keep[PurgeCategory.CONTAINERS], "what is not said stays the default")
        assertTrue(complaints.isEmpty()) { complaints.toString() }
    }

    @Test
    fun `a value it cannot read keeps the default and never turns into a purge of everything`() {
        val settings = purge(
            """
            [purge]
            auto-purge = "yes"
            interval = "1m"
            keep-blocks = "soon"
            keep-items = "0d"
            """.trimIndent(),
        ).autoPurge

        assertEquals(AutoPurgeSettings(), settings)
        assertEquals(4, complaints.size) { complaints.toString() }
    }

    @Test
    fun `unlimited lifts the entity limit and the rollback radius`() {
        assertEquals(Int.MAX_VALUE, readRollback("entity-restore-limit" to "unlimited").entityRestoreLimit)
        assertEquals(null, readRollback("max-radius" to "unlimited").rollbackMaxRadius)
        assertEquals(256, readRollback("max-radius" to 256).rollbackMaxRadius)
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `every kind of history is logged unless the config says otherwise`() {
        assertEquals(LoggingSettings(), readSettings(advanced = null).logging)
        val off = readSettings(advanced = null, logging = Toml.parse("items = false\nevents = false"))
        assertEquals(LoggingSettings(blocks = true, items = false, entities = true, events = false), off.logging)
    }

    @Test
    fun `worldedit edits are logged unless the config says otherwise`() {
        assertTrue(readSettings(advanced = null).logging.worldEdit)
        assertFalse(readSettings(advanced = null, logging = Toml.parse("worldedit = false")).logging.worldEdit)
        assertTrue(readSettings(advanced = null, logging = Toml.parse("worldedit = true")).logging.worldEdit)
    }
}
