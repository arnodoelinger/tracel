package com.tracel.plugin.bukkit.entity.nbt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** NBT tag type numbers, the ones this plugin has to tell apart. */
internal object NbtType {
    const val END = 0
    const val BYTE = 1
    const val SHORT = 2
    const val INT = 3
    const val LONG = 4
    const val FLOAT = 5
    const val DOUBLE = 6
    const val BYTES = 7
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INTS = 11
    const val LONGS = 12
}

/**
 * One named value of a compound. The payload is the value's own bytes, untouched,
 * so whatever is not edited survives as it was.
 */
internal class NbtField(val type: Int, val name: String, val payload: ByteArray)

/** A compound taken apart one level deep: its root name and its fields in file order. */
internal class NbtCompound(val rootName: String, val fields: List<NbtField>) {
    operator fun get(name: String): NbtField? = fields.firstOrNull { it.name == name }
}

/** Just enough NBT to cut fields out of a compound and put others in. */
internal object Nbt {
    private const val MAX_DEPTH = 64

    fun read(bytes: ByteArray): NbtCompound? = runCatching {
        val buf = ByteBuffer.wrap(bytes)
        if (buf.get().toInt() != NbtType.COMPOUND) return null
        val rootName = readString(buf)
        val fields = body(buf, 0)
        if (buf.hasRemaining()) return null
        NbtCompound(rootName, fields)
    }.getOrNull()

    fun write(compound: NbtCompound): ByteArray {
        val out = ByteArrayOutputStream(compound.fields.sumOf { it.payload.size + it.name.length + 3 } + 8)
        out.write(NbtType.COMPOUND)
        writeString(out, compound.rootName)
        writeBody(out, compound.fields)
        return out.toByteArray()
    }

    fun fieldsOf(payload: ByteArray): List<NbtField>? = runCatching {
        val buf = ByteBuffer.wrap(payload)
        body(buf, 0).also { if (buf.hasRemaining()) return null }
    }.getOrNull()

    fun bodyOf(fields: List<NbtField>): ByteArray =
        ByteArrayOutputStream().also { writeBody(it, fields) }.toByteArray()

    fun listOf(elementType: Int, elements: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(elementType)
        writeInt(out, elements.size)
        for (e in elements) out.write(e)
        return out.toByteArray()
    }

    fun compoundElements(payload: ByteArray): List<ByteArray>? = runCatching {
        val buf = ByteBuffer.wrap(payload)
        val type = buf.get().toInt()
        val n = buf.int
        if (n == 0) return emptyList()
        if (type != NbtType.COMPOUND) return null
        List(n) {
            val start = buf.position()
            skip(buf, NbtType.COMPOUND, 0)
            payload.copyOfRange(start, buf.position())
        }
    }.getOrNull()

    fun stringOf(payload: ByteArray): String? = runCatching { readString(ByteBuffer.wrap(payload)) }.getOrNull()

    fun doubleOf(payload: ByteArray): Double? = if (payload.size == 8) ByteBuffer.wrap(payload).double else null

    fun string(value: String): ByteArray {
        val raw = value.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(2 + raw.size).putShort(raw.size.toShort()).put(raw).array()
    }

    fun doubles(vararg values: Double): ByteArray =
        ByteBuffer.allocate(5 + 8 * values.size).put(NbtType.DOUBLE.toByte()).putInt(values.size)
            .also { b -> values.forEach { b.putDouble(it) } }.array()

    fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(5 + 4 * values.size).put(NbtType.FLOAT.toByte()).putInt(values.size)
            .also { b -> values.forEach { b.putFloat(it) } }.array()

    fun ints(vararg values: Int): ByteArray =
        ByteBuffer.allocate(4 + 4 * values.size).putInt(values.size).also { b -> values.forEach { b.putInt(it) } }.array()

    private fun body(buf: ByteBuffer, depth: Int): List<NbtField> {
        check(depth < MAX_DEPTH) { "nbt nested too deep." }
        val out = ArrayList<NbtField>()
        while (true) {
            val type = buf.get().toInt()
            if (type == NbtType.END) return out
            val name = readString(buf)
            val start = buf.position()
            skip(buf, type, depth)
            out += NbtField(type, name, ByteArray(buf.position() - start).also { buf.get(start, it) })
        }
    }

    private fun skip(buf: ByteBuffer, type: Int, depth: Int) {
        when (type) {
            NbtType.BYTE -> buf.position(buf.position() + 1)
            NbtType.SHORT -> buf.position(buf.position() + 2)
            NbtType.INT, NbtType.FLOAT -> buf.position(buf.position() + 4)
            NbtType.LONG, NbtType.DOUBLE -> buf.position(buf.position() + 8)
            NbtType.BYTES -> buf.int.let { buf.position(buf.position() + it) }
            NbtType.STRING -> (buf.short.toInt() and 0xFFFF).let { buf.position(buf.position() + it) }
            NbtType.INTS -> buf.int.let { buf.position(buf.position() + 4 * it) }
            NbtType.LONGS -> buf.int.let { buf.position(buf.position() + 8 * it) }
            NbtType.LIST -> {
                check(depth < MAX_DEPTH) { "nbt nested too deep." }
                val element = buf.get().toInt()
                val n = buf.int
                check(n >= 0) { "negative list." }
                repeat(n) { skip(buf, element, depth + 1) }
            }

            NbtType.COMPOUND -> body(buf, depth + 1)
            else -> error("unknown nbt tag $type.")
        }
        check(buf.position() <= buf.limit()) { "nbt runs past its end." }
    }

    private fun readString(buf: ByteBuffer): String {
        val n = buf.short.toInt() and 0xFFFF
        val raw = ByteArray(n)
        buf.get(raw)
        return String(raw, StandardCharsets.UTF_8)
    }

    private fun writeBody(out: ByteArrayOutputStream, fields: List<NbtField>) {
        for (f in fields) {
            out.write(f.type)
            writeString(out, f.name)
            out.write(f.payload)
        }
        out.write(NbtType.END)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) = out.write(string(value))

    private fun writeInt(out: ByteArrayOutputStream, value: Int) =
        out.write(ByteBuffer.allocate(4).putInt(value).array())
}
