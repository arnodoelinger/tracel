package com.tracel.storage.codec

import com.tracel.storage.codec.Field.*
import com.tracel.storage.spi.EngineCursor
import java.util.*

/** How a key field is laid out. Big-endian always, so unsigned byte order is numeric order. */
enum class Field(val size: Int) {
    U8(1),
    U32(4),
    ORD32(4),
    U64(8),
    INV64(8),
    UUID(16),
    RAW(0),
}

/**
 * The whole keyspace: a tag byte and the fields that follow it.
 *
 * Two rules hold everywhere and everything else follows from them:
 *
 * 1. Keys are big-endian, values are little-endian. Keys are compared as unsigned bytes, so
 *    only big-endian puts numbers in numeric order; values are never compared, so they are
 *    written in the machine's own order and a load is a load.
 * 2. Every sequence number in a secondary index is stored inverted ([Field.INV64]).
 *
 * `Tag.ACTOR[holderId, seq]` is a key, `Tag.ACTOR[holderId]` the prefix of one, `Tag.ACTOR.prefix` the whole family.
 * Arguments go in byte order and the fields do the inverting, so never pass an inverted number.
 */
enum class Tag(val byte: Byte, private vararg val fields: Field) {
    TXN(0x01, U64),
    TXN_BY_ID(0x02, U64),
    ACTOR(0x03, U32, INV64),
    ITEM(0x04, U32, INV64),
    TIME(0x05, INV64, INV64),
    SPATIAL(0x06, U32, ORD32, ORD32, INV64, U8, INV64),
    LOT(0x07, U64),
    PLACE(0x08),
    PLACE_REV(0x09),
    PLACE_ITEM(0x0A),
    TOTAL(0x0B, U32, U32),
    EDGE_FROM(0x0C, U64, U64),
    EDGE_INTO(0x0D, U64, U64),
    LEASE(0x0E, U64),
    LEASE_JOB(0x0F, U64, U64),
    RB_STEP(0x10, U64, U32),
    RB_JOB(0x11, U64),
    APPLIED(0x12, U8, U64, U32),
    PENDING(0x13, UUID, U64),
    COUNTER(0x14, U32),
    INTERN_FORWARD(0x15, U8, U32),
    INTERN_REVERSE(0x16, U8, RAW), // Retired
    WCHG(0x17, U64),
    WCHG_AT(0x18, U32, ORD32, ORD32, ORD32, INV64),
    WCHG_AT_SECTION(0x20, U32, ORD32, ORD32, ORD32, INV64),
    WCHG_ENTITY(0x19, UUID, INV64),
    TXN_LOT(0x1A, U64, U32, U64),
    BLOCK_LEASE(0x1B, U32, ORD32, ORD32, ORD32),
    RB_STRUCT(0x1C, U64, U32),
    RB_TARGET(0x1D, U64, U64),
    RB_RECENT(0x1E, INV64),
    ITEM_FORM(0x1F, RAW),
    CONTAINER_SLOT(0x21, U32, INV64),
    GROUND_AT(0x22, U32),
    WEAR(0x23, U64, U64),
    LEASE_SET(0x24, U64),
    PACK(0x25, U64),
    PACK_AT(0x26, U32, U32, U64),
    PACK_ITEM(0x27, U32, U32, U64),
    LOT_PACK(0x28, U64),
    ACTOR_KIND(0x29, U32),
    ACTOR_MODE(0x2A, U32, INV64),
    ACTOR_VISIT(0x2B, U32, INV64),
    IMPORT_MARK(0x2C, U64),
    EVENT(0x2D, U64),
    EVENT_ACTOR(0x2E, U32, INV64, INV64),
    EVENT_TIME(0x2F, INV64, INV64),
    EVENT_ENTITY(0x35, UUID, INV64),
    ROLLED(0x30), // Retired
    ROLLED_JOB(0x31), // Retired
    META(0x32, U8),
    ROLLED_SET(0x33, U64, U64),
    ROLLED_SET_JOB(0x34, U64, U64),
    OUTSIDE_STEP(0x36, U64),
    BLOCK_NOTE(0x37, U32, ORD32, ORD32, ORD32),
    LIVE_ENTITY(0x38, UUID),
    PENDING_WEAR(0x39, UUID, U64),
    CHRONO_CAPSULE(0x3A, U32, ORD32, ORD32, U8, INV64),
    CHRONO_TRACK(0x3B, U32, INV64, U32, ORD32, ORD32, U8),
    CHRONO_TIME(0x3C, INV64, U32, ORD32, ORD32, U8),
    CHRONO_MARK(0x3D), // Retired
    CHRONO_FRAGMENT(0x3E, U32, ORD32, ORD32, U8, INV64);

    val label: String = name.lowercase().split('_')
        .mapIndexed { i, word -> if (i == 0) word else word.replaceFirstChar(Char::uppercase) }.joinToString("")

    val prefix: ByteArray get() = byteArrayOf(byte)

    /** The key of [parts], or the prefix of one when they stop short of the last field. */
    operator fun get(vararg parts: Any): ByteArray {
        require(parts.size <= fields.size) { "$label takes ${fields.size} fields, got ${parts.size}." }
        var size = 1
        for (i in parts.indices) size += if (fields[i] == RAW) (parts[i] as ByteArray).size else fields[i].size
        val writer = KeyWriter(size).tag(byte)
        for (i in parts.indices) {
            val part = parts[i]
            when (fields[i]) {
                UUID -> writer.u64((part as UUID).mostSignificantBits).u64(part.leastSignificantBits)
                RAW -> writer.raw(part as ByteArray)
                else -> number(writer, fields[i], (part as Number).toLong())
            }
        }
        return writer.done()
    }

    /** [get] for the single number that most keys are looked up by, without boxing it. */
    operator fun get(first: Long): ByteArray {
        require(fields.isNotEmpty()) { "$label has no fields." }
        return number(KeyWriter(1 + fields[0].size).tag(byte), fields[0], first).done()
    }

    fun int(key: ByteArray, field: Int): Int = intOf(KeyReader.u32(key, offsets[field]), fields[field])

    fun u8(key: ByteArray, field: Int): Int = key[offsets[field]].toInt() and 0xFF

    fun u8(cursor: EngineCursor, field: Int): Int = cursor.keyByte(offsets[field]).toInt() and 0xFF

    fun int(cursor: EngineCursor, field: Int): Int = intOf(cursor.keyU32(offsets[field]), fields[field])

    fun long(key: ByteArray, field: Int): Long = longOf(KeyReader.u64(key, offsets[field]), fields[field])

    fun long(cursor: EngineCursor, field: Int): Long = longOf(cursor.keyU64(offsets[field]), fields[field])

    fun uuid(cursor: EngineCursor, field: Int): UUID = UUID(cursor.keyU64(offsets[field]), cursor.keyU64(offsets[field] + 8))

    fun raw(key: ByteArray, field: Int): ByteArray = key.copyOfRange(offsets[field], key.size)

    private val offsets = IntArray(fields.size).also { offsets ->
        var at = 1
        for (i in fields.indices) {
            offsets[i] = at
            at += fields[i].size
        }
    }

    private fun number(writer: KeyWriter, field: Field, value: Long): KeyWriter = when (field) {
        U8 -> writer.u8(value.toByte())
        U32 -> writer.u32(value.toInt())
        ORD32 -> writer.u32(Keys.ordered(value.toInt()))
        U64 -> writer.u64(value)
        INV64 -> writer.u64(Keys.invert(value))
        else -> error("$label: $field is not a number.")
    }

    private fun intOf(raw: Int, field: Field): Int = if (field == ORD32) Keys.ordered(raw) else raw

    private fun longOf(raw: Long, field: Field): Long = if (field == INV64) Keys.invert(raw) else raw

    companion object {
        private val BY_BYTE: Array<Tag?> = arrayOfNulls<Tag>(256).also { table ->
            for (tag in entries) table[tag.byte.toInt() and 0xFF] = tag
        }

        fun of(byte: Byte): Tag? = BY_BYTE[byte.toInt() and 0xFF]

        fun labelOf(byte: Byte): String = of(byte)?.label ?: "tag%02x".format(byte.toInt() and 0xFF)

        val KEEPS_NUMBERING: List<Tag> =
            listOf(COUNTER, INTERN_FORWARD, ITEM_FORM, ACTOR_KIND, ACTOR_MODE, META)
    }
}
