package com.tracel.plugin

import com.tracel.storage.TracelStorage
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.write.SyncPolicy
import org.junit.jupiter.api.Assertions.assertEquals
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
        return readSettings(storage = null, rollback = Toml.parse(body), complain = complaints::add)
    }

    private fun toml(value: Any?): String = when (value) {
        is String -> "\"$value\""
        else -> value.toString()
    }

    @Test
    fun `the shipped config toml is exactly the defaults, written out`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/config.toml")) { "config.toml is not on the classpath" }
            .reader().readText()
        val config = Toml.parse(text)

        assertEquals(Settings(), readSettings(config.getTable("storage"), config.getTable("rollback"), complaints::add))
        assertTrue(complaints.isEmpty()) { complaints.toString() }
    }

    @Test
    fun `an empty section is every default`() {
        assertEquals(Settings(), read())
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `sync takes the two words and any interval`() {
        assertEquals(SyncPolicy.EveryBatch, read("sync" to "every-batch").lsm.sync)
        assertEquals(SyncPolicy.Never, read("sync" to "NEVER").lsm.sync)
        assertEquals(SyncPolicy.Interval(500), read("sync" to "500ms").lsm.sync)
        assertEquals(SyncPolicy.Interval(5_000), read("sync" to "5s").lsm.sync)
        assertEquals(SyncPolicy.Interval(60_000), read("sync" to "1m").lsm.sync)
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `sizes come in the units an admin writes them in`() {
        assertEquals(16L shl 20, read("memtable-size" to "16MiB").lsm.memtableBytes)
        assertEquals(4L shl 20, read("memtable-size" to "4MB").lsm.memtableBytes)
        assertEquals(2L shl 30, read("memtable-size" to "2GiB").lsm.memtableBytes)
        assertEquals(8L shl 20, read("memtable-size" to 8_388_608).lsm.memtableBytes)
        assertTrue(complaints.isEmpty())
    }

    @Test
    fun `a value it cannot read keeps the default and says which key`() {
        val settings = read("sync" to "sometimes", "memtable-size" to "lots")

        assertEquals(LsmConfig().sync, settings.lsm.sync)
        assertEquals(LsmConfig().memtableBytes, settings.lsm.memtableBytes)
        assertEquals(2, complaints.size)
        assertTrue(complaints.any { it.contains("storage.sync") && it.contains("sometimes") }) { complaints.toString() }
        assertTrue(complaints.any { it.contains("storage.memtable-size") }) { complaints.toString() }
    }

    @Test
    fun `every fallback is the safe direction, so a typo costs speed and never durability`() {
        assertEquals(SyncPolicy.EveryBatch, read("sync" to "nevr").lsm.sync)
        assertEquals(SyncPolicy.EveryBatch, read("sync" to "every batch").lsm.sync)
    }

    @Test
    fun `numbers that would stall the server are refused rather than obeyed`() {
        assertEquals(LsmConfig().memtableBytes, read("memtable-size" to "4KiB").lsm.memtableBytes)
        assertEquals(LsmConfig().maxFrozenMemtables, read("max-pending-flushes" to 0).lsm.maxFrozenMemtables)
        assertEquals(2, complaints.size)
    }

    @Test
    fun `the capture ring has to be a power of two`() {
        assertEquals(1 shl 18, read("capture-ring-slots" to (1 shl 18)).ringSlots)
        assertEquals(TracelStorage.DEFAULT_RING_SLOTS, read("capture-ring-slots" to 65_000).ringSlots)
        assertEquals(TracelStorage.DEFAULT_RING_SLOTS, read("capture-ring-slots" to 512).ringSlots)
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
}
