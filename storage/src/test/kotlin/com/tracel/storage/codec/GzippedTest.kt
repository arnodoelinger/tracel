package com.tracel.storage.codec

import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.entity.EntityExtras
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class GzippedTest {
    private val nbt = ("{id:\"minecraft:chest\",Items:[" + (0 until 27).joinToString { "{Slot:${it}b,id:\"minecraft:stone\",count:64}" } + "]}")
        .toByteArray()

    private fun gzip(plain: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val out = ByteArrayOutputStream()
        object : GZIPOutputStream(out) {
            init {
                def.setLevel(level)
            }
        }.use { it.write(plain) }
        return out.toByteArray()
    }

    @Test
    fun `gzipped block extras are kept inflated and come back exactly`() {
        val bytes = gzip(nbt)
        val stored = Extras.block(BlockExtras.Opaque(bytes))
        assertEquals(5, stored[0].toInt(), "kept inflated")
        assertTrue(stored.size > bytes.size)
        assertArrayEquals(bytes, (Extras.decodeBlock(stored) as BlockExtras.Opaque).bytes)
    }

    @Test
    fun `gzipped entity extras nested in a link come back exactly`() {
        val bytes = gzip(nbt)
        val holder = UUID.randomUUID()
        val stored = Extras.entity(EntityExtras.Leashed(holder, EntityExtras.Opaque(bytes)))
        val back = Extras.decodeEntity(stored) as EntityExtras.Leashed
        assertEquals(holder, back.holder)
        assertArrayEquals(bytes, (back.rest as EntityExtras.Opaque).bytes)
    }

    @Test
    fun `gzip that would not come back the same stays as it is`() {
        val odd = gzip(nbt, level = 1)
        val stored = Extras.block(BlockExtras.Opaque(odd))
        assertEquals(0, stored[0].toInt())
        assertArrayEquals(odd, (Extras.decodeBlock(stored) as BlockExtras.Opaque).bytes)

        val plain = Extras.block(BlockExtras.Opaque(nbt))
        assertEquals(0, plain[0].toInt())
        assertArrayEquals(nbt, (Extras.decodeBlock(plain) as BlockExtras.Opaque).bytes)
    }
}
