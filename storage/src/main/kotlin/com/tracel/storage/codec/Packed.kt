package com.tracel.storage.codec

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.EntityTypeKey
import com.tracel.storage.codec.Packed.BLOCK
import com.tracel.storage.codec.Packed.ENTITY
import com.tracel.storage.codec.Packed.ITEM_ENTITY
import com.tracel.storage.codec.Packed.PLACED_BLOCK
import com.tracel.storage.codec.Packed.PLACED_ENTITY
import com.tracel.storage.codec.Packed.PLAYER
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.readBytes
import java.lang.foreign.MemorySegment
import java.util.*

/**
 * Binary encodings of the two things worth interning.
 *
 * | Category / Type                         | Tag                   | Size (bytes)                | Memory Layout                                                                                     |
 * | :-------------------------------------- | :-------------------- | :-------------------------- | :------------------------------------------------------------------------------------------------ |
 * | Holder: Block / PlacedBlock             | `0` / `1` / 29        | `+0` tag (u8)               | `+1` world UUID (i64 + i64) / `+17` x (i32) / `+21` y (i32) / `+25` z (i32)                       |
 * | Holder: Player / Entity / ItemEntity    | `2` / `3` / `4` | 17  | `+0` tag (u8)               | `+1` entity UUID (i64 + i64)                                                                      |
 * | Holder: PlacedEntity / EnderChest       | `8` / `9` / 17        | `+0` tag (u8)               | `+1` entity UUID (i64 + i64)                                                                      |
 * | Holder: Escrow                          | `5` / 9               | `+0` tag (u8)               | `+1` jobId (i64)                                                                                  |
 * | Holder: Source / Sink                   | `6` / `7` / 2         | `+0` tag (u8)               | `+1` kind ordinal (u8)                                                                            |
 * | WorldId                                 | —       / 16          | `+0` world UUID (i64 + i64) | —                                                                                                 |
 * | ItemKey                                 | —       / `4 + M + D` | `+0` materialLen (u16 BE)   | `+2` material (UTF-8) / `+2+M` decorationLen (u16 BE) / `+4+M` decoration (UTF-8)                 |
 * | BlockDataKey / EntityTypeKey            | —       / `2 + N`     | `+0` length (u16 BE)        | `+2` text (UTF-8)                                                                                 |
 */
object Packed {
    private const val BLOCK: Byte = 0
    private const val PLACED_BLOCK: Byte = 1
    private const val PLAYER: Byte = 2
    private const val ENTITY: Byte = 3
    private const val ITEM_ENTITY: Byte = 4
    private const val ESCROW: Byte = 5
    private const val SOURCE: Byte = 6
    private const val SINK: Byte = 7
    private const val PLACED_ENTITY: Byte = 8
    private const val ENDER_CHEST: Byte = 9

    /**
     * Encodes a [HolderId] into its compact fixed-size binary representation.
     *
     * @param holder the typed domain holder identifier
     * @return a newly allocated byte array containing the tag and raw payload
     */
    fun holder(holder: HolderId): ByteArray = when (holder) {
        is HolderId.Block -> block(BLOCK, holder.world, holder.x, holder.y, holder.z)
        is HolderId.PlacedBlock -> block(PLACED_BLOCK, holder.world, holder.x, holder.y, holder.z)
        is HolderId.Player -> uuid(PLAYER, holder.uuid)
        is HolderId.EnderChest -> uuid(ENDER_CHEST, holder.uuid)
        is HolderId.Entity -> uuid(ENTITY, holder.uuid)
        is HolderId.PlacedEntity -> uuid(PLACED_ENTITY, holder.uuid)
        is HolderId.ItemEntity -> uuid(ITEM_ENTITY, holder.uuid)
        is HolderId.Escrow -> ByteArray(9).also {
            val s = MemorySegment.ofArray(it)
            s.putI8(0, ESCROW); s.putI64(1, holder.job.raw)
        }

        is HolderId.Source -> byteArrayOf(SOURCE, holder.kind.ordinal.toByte())
        is HolderId.Sink -> byteArrayOf(SINK, holder.kind.ordinal.toByte())
    }

    /**
     * Decodes a [HolderId] directly from an off-heap memory segment.
     *
     * @param v segment pointing to the start of the encoded holder
     * @return reconstituted domain object
     * @throws IllegalStateException if the leading tag byte is unknown
     */
    fun decodeHolder(v: MemorySegment): HolderId = when (val tag = v.i8(0)) {
        BLOCK -> HolderId.Block(WorldId(UUID(v.i64(1), v.i64(9))), v.i32(17), v.i32(21), v.i32(25))
        PLACED_BLOCK -> HolderId.PlacedBlock(WorldId(UUID(v.i64(1), v.i64(9))), v.i32(17), v.i32(21), v.i32(25))
        PLAYER -> HolderId.Player(UUID(v.i64(1), v.i64(9)))
        ENDER_CHEST -> HolderId.EnderChest(UUID(v.i64(1), v.i64(9)))
        ENTITY -> HolderId.Entity(UUID(v.i64(1), v.i64(9)))
        PLACED_ENTITY -> HolderId.PlacedEntity(UUID(v.i64(1), v.i64(9)))
        ITEM_ENTITY -> HolderId.ItemEntity(UUID(v.i64(1), v.i64(9)))
        ESCROW -> HolderId.Escrow(RollbackJobId(v.i64(1)))
        SOURCE -> HolderId.Source(SourceKind.entries[v.i8(1).toInt()])
        SINK -> HolderId.Sink(SinkKind.entries[v.i8(1).toInt()])
        else -> error("unrecognized holder tag: $tag")
    }

    /**
     * Encodes an [ItemKey] into a length-prefixed binary layout.
     *
     * Writes material UTF-8 bytes prefixed by `u16 BE` length, followed by optional
     * decoration hash UTF-8 bytes prefixed by `u16 BE` length (or zero length if null).
     *
     * @param itemKey item descriptor containing material name and optional hash
     * @return byte array of size `4 + materialLength + decorationLength`
     */
    fun itemKey(itemKey: ItemKey): ByteArray {
        val material = itemKey.material.toByteArray(Charsets.UTF_8)
        val decoration = itemKey.decoration?.hex?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val out = ByteArray(4 + material.size + decoration.size)
        out[0] = (material.size ushr 8).toByte()                  // Material length high byte
        out[1] = material.size.toByte()                           // Material length low byte
        material.copyInto(out, 2)

        var at = 2 + material.size
        out[at] = (decoration.size ushr 8).toByte()               // Decoration length high byte
        out[at + 1] = decoration.size.toByte()                    // Decoration length low byte
        at += 2
        decoration.copyInto(out, at)
        return out
    }

    /**
     * Decodes an [ItemKey] from an off-heap memory segment.
     *
     * @param v segment containing the length-prefixed item payload
     * @return parsed [ItemKey] instance
     */
    fun decodeItemKey(v: MemorySegment): ItemKey {
        val materialLen = ((v.i8(0).toInt() and 0xFF) shl 8) or (v.i8(1).toInt() and 0xFF)
        val material = String(v.readBytes(2, materialLen), Charsets.UTF_8)
        val at = 2L + materialLen
        val decorationLen = ((v.i8(at).toInt() and 0xFF) shl 8) or (v.i8(at + 1).toInt() and 0xFF)
        val decoration =
            if (decorationLen == 0) null else ContentHash(String(v.readBytes(at + 2, decorationLen), Charsets.UTF_8))
        return ItemKey(material, decoration)
    }

    /** Encodes a [BlockDataKey]. */
    fun blockData(blockData: BlockDataKey): ByteArray = text(blockData.value)

    /** Decodes block data. */
    fun decodeBlockData(v: MemorySegment): BlockDataKey = BlockDataKey(decodeText(v))

    /** Encodes an [EntityTypeKey] — `minecraft:chest_boat`. Same shape as [blockData]. */
    fun entityType(entityType: EntityTypeKey): ByteArray = text(entityType.value)

    /** Decodes entity type. */
    fun decodeEntityType(v: MemorySegment): EntityTypeKey = EntityTypeKey(decodeText(v))

    /**
     * Serializes a [WorldId] into 16 raw big-endian bytes (128-bit UUID).
     *
     * @param world domain world wrapper
     * @return 16-byte array containing most and least significant bits
     */
    fun world(world: WorldId): ByteArray = ByteArray(16).also {
        val s = MemorySegment.ofArray(it)
        s.putI64(0, world.uuid.mostSignificantBits)     // Bits 0–63
        s.putI64(8, world.uuid.leastSignificantBits)    // Bits 64–127
    }

    /**
     * Encodes a UTF-8 string with a 2-byte big-endian length prefix.
     *
     * @return byte array of size `2 + utf8.length`
     */
    private fun text(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + utf8.size)
        out[0] = (utf8.size ushr 8).toByte()
        out[1] = utf8.size.toByte()
        utf8.copyInto(out, 2)
        return out
    }

    /**
     * Decodes a UTF-8 string from a 2-byte big-endian length prefix.
     *
     * @param v segment containing the length-prefixed UTF-8 string
     * @return reconstituted string
     */
    private fun decodeText(v: MemorySegment): String {
        val length = ((v.i8(0).toInt() and 0xFF) shl 8) or (v.i8(1).toInt() and 0xFF)
        return String(v.readBytes(2, length), Charsets.UTF_8)
    }

    /**
     * Reads back what [world] wrote.
     *
     * @param v segment containing the world identifier
     * @return decoded world identifier
     */
    fun decodeWorld(v: MemorySegment): WorldId = WorldId(UUID(v.i64(0), v.i64(8)))

    /**
     * Helper for encoding spatial block holders (29 bytes).
     *
     * @param tag discriminator byte ([BLOCK] or [PLACED_BLOCK])
     * @param world world identifier
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     */
    private fun block(tag: Byte, world: WorldId, x: Int, y: Int, z: Int): ByteArray = ByteArray(29).also {
        val s = MemorySegment.ofArray(it)
        s.putI8(0, tag)                                 // Offset +0
        s.putI64(1, world.uuid.mostSignificantBits)     // Offset +1
        s.putI64(9, world.uuid.leastSignificantBits)    // Offset +9
        s.putI32(17, x)                                 // Offset +17
        s.putI32(21, y)                                 // Offset +21
        s.putI32(25, z)                                 // Offset +25
    }

    /**
     * Helper for encoding UUID-based entity holders (17 bytes).
     *
     * @param tag discriminator byte ([PLAYER], [ENTITY], [PLACED_ENTITY], or [ITEM_ENTITY])
     * @param uuid entity unique identifier
     */
    private fun uuid(tag: Byte, uuid: UUID): ByteArray = ByteArray(17).also {
        val s = MemorySegment.ofArray(it)
        s.putI8(0, tag)                           // Offset +0
        s.putI64(1, uuid.mostSignificantBits)     // Offset +1
        s.putI64(9, uuid.leastSignificantBits)    // Offset +9
    }
}
