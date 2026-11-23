package com.tracel.plugin.services.startup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class DeveloperTest {
    @Test
    fun `a development build needs developer mode`() {
        val refused = assertThrows<DeveloperFlagMissing> { Developer.require("1.0.0-dev", on = false) }
        assertTrue("-D${Developer.PROPERTY}=true" in refused.message.orEmpty())
        assertDoesNotThrow { Developer.require("1.0.0-dev", on = true) }
    }

    @Test
    fun `a release runs without it`() {
        assertDoesNotThrow { Developer.require("1.0.0", on = false) }
        assertDoesNotThrow { Developer.require("1.0.0-preview.2", on = false) }
    }
}
