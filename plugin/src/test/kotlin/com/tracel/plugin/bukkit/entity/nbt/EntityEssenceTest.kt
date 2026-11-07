package com.tracel.plugin.bukkit.entity.nbt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityEssenceTest {
    private fun <T : Any> some(value: T?): T = value ?: throw AssertionError("expected a value.")

    private fun gzip(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun byte(name: String, v: Int) = NbtField(NbtType.BYTE, name, byteArrayOf(v.toByte()))
    private fun float(name: String, v: Float) = NbtField(NbtType.FLOAT, name, ByteBuffer.allocate(4).putFloat(v).array())
    private fun double(v: Double) = ByteBuffer.allocate(8).putDouble(v).array()
    private fun str(name: String, v: String) = NbtField(NbtType.STRING, name, Nbt.string(v))

    private fun attribute(id: String, base: Double, vararg modifiers: String): ByteArray {
        val fields = mutableListOf(
            NbtField(NbtType.DOUBLE, "base", double(base)),
            str("id", id),
        )
        if (modifiers.isNotEmpty()) {
            val list = modifiers.map { Nbt.bodyOf(listOf(str("id", it), NbtField(NbtType.DOUBLE, "amount", double(0.03)))) }
            fields += NbtField(NbtType.LIST, "modifiers", Nbt.listOf(NbtType.COMPOUND, list))
        }
        return Nbt.bodyOf(fields)
    }

    private fun zombie(): ByteArray {
        val attributes = Nbt.listOf(
            NbtType.COMPOUND,
            listOf(
                attribute("minecraft:movement_speed", 0.23),
                attribute("minecraft:spawn_reinforcements", 0.0117),
                attribute("minecraft:follow_range", 35.0, "minecraft:random_spawn_bonus"),
                attribute("minecraft:max_health", 40.0),
                attribute("minecraft:armor", 2.0, "plugin:armour_charm"),
            ),
        )
        val fields = listOf(
            str("id", "minecraft:zombie"),
            float("Health", 17.5f),
            str("CustomName", "Bob"),
            NbtField(NbtType.LIST, "Pos", Nbt.doubles(1.5, 64.0, 2.5)),
            NbtField(NbtType.LIST, "Motion", Nbt.doubles(0.0, -0.08, 0.0)),
            NbtField(NbtType.LIST, "Rotation", Nbt.floats(10f, 20f)),
            NbtField(NbtType.INTS, "UUID", Nbt.ints(1, 2, 3, 4)),
            NbtField(NbtType.COMPOUND, "Brain", Nbt.bodyOf(listOf(byte("x", 1)))),
            byte("OnGround", 1),
            NbtField(NbtType.LIST, "attributes", attributes),
            NbtField(NbtType.COMPOUND, "BukkitValues", Nbt.bodyOf(listOf(str("other:tag", "kept")))),
            NbtField(NbtType.LONGS, "Strange", ByteBuffer.allocate(20).putInt(2).putLong(7).putLong(8).array()),
        )
        return gzip(Nbt.write(NbtCompound("", fields)))
    }

    private fun parse(gzipped: ByteArray): NbtCompound = some(Nbt.read(gunzip(gzipped)))

    @Test
    fun `slimming cuts weather and keeps what a restore needs`() {
        val slim = some(EntityEssence.slim(zombie()) { id -> if (id == "minecraft:movement_speed") 0.23 else null })
        val names = parse(slim).fields.map { it.name }
        assertEquals(
            listOf("id", "Health", "CustomName", "attributes", "BukkitValues", "Strange"), names,
            "position, motion, rotation, uuid, brain and ground state are gone, all else stays in order.",
        )
        assertArrayEquals(zombie().let { parse(it)["Strange"]!!.payload }, parse(slim)["Strange"]!!.payload)
    }

    private val species = mapOf("minecraft:movement_speed" to 0.23, "minecraft:follow_range" to 35.0)

    @Test
    fun `attributes keep only what is the player's or the plugin's`() {
        val slim = some(EntityEssence.slim(zombie()) { species[it] })
        val entries = Nbt.compoundElements(parse(slim)["attributes"]!!.payload)!!
        val ids = entries.map { Nbt.stringOf(Nbt.fieldsOf(it)!!.first { f -> f.name == "id" }.payload) }
        assertEquals(listOf("minecraft:max_health", "minecraft:armor"), ids)
    }

    @Test
    fun `a species that cannot say its defaults loses no attribute but the dice`() {
        val slim = some(EntityEssence.slim(zombie()) { null })
        val entries = Nbt.compoundElements(parse(slim)["attributes"]!!.payload)!!
        val ids = entries.map { Nbt.stringOf(Nbt.fieldsOf(it)!!.first { f -> f.name == "id" }.payload) }
        assertEquals(listOf("minecraft:movement_speed", "minecraft:follow_range", "minecraft:max_health", "minecraft:armor"), ids)
        val follow = Nbt.fieldsOf(entries[1])!!
        assertNull(follow.firstOrNull { it.name == "modifiers" }, "the dice modifier went and left no empty list.")
    }

    @Test
    fun `inflating puts back a uuid, a position, a facing and a standstill`() {
        val slim = some(EntityEssence.slim(zombie()) { null })
        val uuid = UUID(0x1122334455667788L, -0x1122334455667788L)
        val back = parse(EntityEssence.inflate(slim, uuid, 3.5, 70.0, -4.5, 90f, -5f))
        assertArrayEquals(Nbt.ints(0x11223344, 0x55667788, -0x11223345, -0x55667788), back["UUID"]!!.payload)
        assertArrayEquals(Nbt.doubles(3.5, 70.0, -4.5), back["Pos"]!!.payload)
        assertArrayEquals(Nbt.floats(90f, -5f), back["Rotation"]!!.payload)
        assertArrayEquals(Nbt.doubles(0.0, 0.0, 0.0), back["Motion"]!!.payload)
        assertEquals("Bob", Nbt.stringOf(back["CustomName"]!!.payload))
    }

    @Test
    fun `a blob that was never slimmed comes back as it came`() {
        val full = zombie()
        assertArrayEquals(full, EntityEssence.inflate(full, UUID(1, 2), 9.0, 9.0, 9.0, 1f, 1f))
    }

    @Test
    fun `what is not gzipped nbt is left to the caller`() {
        assertNull(EntityEssence.slim(ByteArray(40)) { null })
        val junk = gzip(byteArrayOf(10, 0, 0, 99, 1, 2, 3))
        assertNull(EntityEssence.slim(junk) { null })
        assertArrayEquals(junk, EntityEssence.inflate(junk, null, 0.0, 0.0, 0.0, 0f, 0f))
    }

    @Test
    fun `slimming makes a blob much smaller`() {
        val full = zombie()
        val slim = some(EntityEssence.slim(full) { null })
        assertTrue(gunzip(slim).size < gunzip(full).size)
    }
}
