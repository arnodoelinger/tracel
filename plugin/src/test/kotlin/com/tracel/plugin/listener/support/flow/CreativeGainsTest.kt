package com.tracel.plugin.listener.support.flow

import com.tracel.model.item.ItemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreativeGainsTest {
    private val book = ItemKey("minecraft:writable_book")
    private val disc = ItemKey("minecraft:music_disc_cat")
    private val apple = ItemKey("minecraft:apple")

    @Test
    fun `a book put on an empty lectern is all gain`() {
        assertEquals(mapOf(book to 1L), creativeGains(before = emptyMap(), after = mapOf(book to 1L)))
    }

    @Test
    fun `only what came in counts, not what was already there`() {
        val gains = creativeGains(before = mapOf(apple to 2L), after = mapOf(apple to 2L, disc to 1L))

        assertEquals(mapOf(disc to 1L), gains)
    }

    @Test
    fun `a swap gave something back and is no creative gain`() {
        assertTrue(creativeGains(before = mapOf(apple to 1L), after = mapOf(book to 1L)).isEmpty())
    }

    @Test
    fun `taking the book out gains nothing`() {
        assertTrue(creativeGains(before = mapOf(book to 1L), after = emptyMap()).isEmpty())
    }

    @Test
    fun `a click that changed nothing gains nothing`() {
        assertTrue(creativeGains(before = mapOf(book to 1L), after = mapOf(book to 1L)).isEmpty())
    }
}
