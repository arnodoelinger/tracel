package com.tracel.plugin.config.migrate

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TomlMigratorTest {
    private val legacy = """
        # my notes
        [rollback]
        # how many
        entity-restore-limit = 100

        [purge]
        auto-purge = true
    """.trimIndent() + "\n"

    @Test
    fun `a file with no version gets one at the bottom and keeps everything else`() {
        val out = TomlMigrator.migrate(legacy, 1, emptyList())
        assertEquals(0, out.from)
        assertEquals(1, out.to)
        assertTrue(out.text.startsWith(legacy.trimEnd()))
        assertTrue(out.text.trimEnd().endsWith("version = 1"))
        assertEquals(1, TomlMigrator.versionOf(out.text))
    }

    @Test
    fun `a file that is up to date is not touched`() {
        val once = TomlMigrator.migrate(legacy, 1, emptyList()).text
        val again = TomlMigrator.migrate(once, 1, emptyList())
        assertFalse(again.changed)
        assertEquals(once, again.text)
    }

    @Test
    fun `new options are added to their section and new sections above the version`() {
        val steps = listOf(
            FileStep(
                2,
                listOf(
                    Change.AddKey("purge", "keep-blocks", "\"90d\"", listOf("# how long")),
                    Change.AddKey("status", "enabled", "true"),
                ),
            ),
        )
        val out = TomlMigrator.migrate(TomlMigrator.migrate(legacy, 1, emptyList()).text, 2, steps).text
        assertTrue("auto-purge = true\n\n# how long\nkeep-blocks = \"90d\"" in out)
        assertTrue(out.indexOf("[status]") < out.indexOf("[version]"))
        assertTrue(out.trimEnd().endsWith("version = 2"))
    }

    @Test
    fun `an option the person already has is not added again`() {
        val steps = listOf(FileStep(1, listOf(Change.AddKey("purge", "auto-purge", "false"))))
        val out = TomlMigrator.migrate(legacy, 1, steps).text
        assertEquals(1, Regex("auto-purge").findAll(out).count())
        assertTrue("auto-purge = true" in out)
    }

    @Test
    fun `a renamed option keeps its value`() {
        val steps = listOf(FileStep(1, listOf(Change.RenameKey("rollback", "entity-restore-limit", "entity-limit"))))
        val out = TomlMigrator.migrate(legacy, 1, steps).text
        assertTrue("entity-limit = 100" in out)
        assertFalse("entity-restore-limit" in out)
    }

    @Test
    fun `a file from a newer version is left as it is`() {
        val newer = legacy + "\n[version]\nversion = 5\n"
        val out = TomlMigrator.migrate(newer, 2, emptyList())
        assertFalse(out.changed)
        assertEquals(newer, out.text)
    }

    @Test
    fun `a file that is not TOML is left as it is`() {
        assertNull(TomlMigrator.versionOf("[broken"))
        assertFalse(TomlMigrator.migrate("[broken", 1, emptyList()).changed)
    }
}
