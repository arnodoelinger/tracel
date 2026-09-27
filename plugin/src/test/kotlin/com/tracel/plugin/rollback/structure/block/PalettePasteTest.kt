package com.tracel.plugin.rollback.structure.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PalettePasteTest {
    @Test
    fun `the paste class loads without a server`() {
        assertNull(PalettePaste.current())
    }

    @Test
    fun `section index follows the world height`() {
        assertEquals(0, PalettePaste.sectionIndex(-64, -4))
        assertEquals(1, PalettePaste.sectionIndex(-48, -4))
        assertEquals(4, PalettePaste.sectionIndex(0, -4))
        assertEquals(23, PalettePaste.sectionIndex(319, -4))
        assertEquals(24, PalettePaste.sectionIndex(320, -4))
        assertEquals(-1, PalettePaste.sectionIndex(-65, -4))
    }
}
