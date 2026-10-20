package com.tracel.plugin.config

import com.tracel.api.access.Grant
import com.tracel.api.admin.PurgeCategory
import com.tracel.api.admin.PurgeRequest
import com.tracel.plugin.config.read.readSettings
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tomlj.Toml

class ApiSettingsTest {
    private val complaints = ArrayList<String>()

    @Test
    fun `destructive operations are off unless the admin turns them on`() {
        assertFalse(ApiSettings().allowDestructive)
        assertFalse(readSettings(advanced = null, api = null, complain = complaints::add).api.allowDestructive)
        assertTrue(
            readSettings(advanced = null, api = Toml.parse("allow-destructive = true"), complain = complaints::add)
                .api.allowDestructive,
        )
    }

    @Test
    fun `allowing them is no grant, and a value that is not a boolean keeps them off`() {
        assertEquals(Grant.entries.toSet(), ApiSettings(allowDestructive = true).grantsOf("Whatever"))

        val wrong = readSettings(advanced = null, api = Toml.parse("allow-destructive = \"yes\""), complain = complaints::add)

        assertFalse(wrong.api.allowDestructive)
        assertEquals(1, complaints.size)
    }

    @Test
    fun `a purge of nothing, or of containers by player, is not a request`() {
        assertThrows<IllegalArgumentException> { PurgeRequest(categories = emptySet()) }
        assertThrows<IllegalArgumentException> {
            PurgeRequest(setOf(PurgeCategory.CONTAINERS), player = UUID(0, 1))
        }
        assertEquals(PurgeCategory.entries.toSet(), PurgeRequest().categories)
    }
}
