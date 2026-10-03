package com.tracel.plugin

import com.tracel.plugin.setup.ConfigEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigEditTest {
    private val text = """
        # comment
        [rollback]
        # note
        entity-restore-limit = 256

        [purge]
        auto-purge = false
        keep-blocks = "90d"
    """.trimIndent() + "\n"

    @Test
    fun `an existing key is replaced where it stands`() {
        val out = ConfigEdit.with(text, "purge", "auto-purge", "true")
        assertTrue("auto-purge = true" in out)
        assertTrue("# note" in out)
        assertEquals(text.lines().size, out.lines().size)
    }

    @Test
    fun `the same key in another section is left alone`() {
        val out = ConfigEdit.with(text, "rollback", "keep-blocks", "\"7d\"")
        assertTrue("keep-blocks = \"90d\"" in out)
        assertTrue("keep-blocks = \"7d\"" in out)
    }

    @Test
    fun `a missing section is added at the end`() {
        val out = ConfigEdit.with(text, "logging", "blocks", "false")
        assertTrue(out.trimEnd().endsWith("[logging]\nblocks = false"))
    }
}
