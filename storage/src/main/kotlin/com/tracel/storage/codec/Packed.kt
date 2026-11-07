package com.tracel.storage.codec

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ContentHash
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.readBytes
import java.lang.foreign.MemorySegment
import java.util.*

/** The binary forms of what gets interned: holders, item keys, worlds, block data and entity types. */
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
    private const val PLUGIN: Byte = 10
    private const val CUSTOM: Byte = 11

    private object At : Record() {
        val tag = i8()
        val world = uuid()
        val x = i32()
        val y = i32()
        val z = i32()
    }

    private object Id : Record() {
        val tag = i8()
        val uuid = uuid()
    }

    private object Escrow : Record() {
        val tag = i8()
        val job = i64()
    }

    private object WorldAt : Record() {
        val uuid = uuid()
    }

    fun holder(holder: HolderId): ByteArray = when (holder) {
        is HolderId.Block -> at(BLOCK, holder.world, holder.x, holder.y, holder.z)
        is HolderId.PlacedBlock -> at(PLACED_BLOCK, holder.world, holder.x, holder.y, holder.z)
        is HolderId.Player -> id(PLAYER, holder.uuid)
        is HolderId.PlayerStash -> id(ENDER_CHEST, holder.uuid)
        is HolderId.Entity -> id(ENTITY, holder.uuid)
        is HolderId.PlacedEntity -> id(PLACED_ENTITY, holder.uuid)
        is HolderId.ItemEntity -> id(ITEM_ENTITY, holder.uuid)
        is HolderId.Escrow -> Escrow.build { Escrow.tag(ESCROW); Escrow.job(holder.job.raw) }
        is HolderId.Source -> byteArrayOf(SOURCE, holder.kind.ordinal.toByte())
        is HolderId.Sink -> byteArrayOf(SINK, holder.kind.ordinal.toByte())
        is HolderId.Plugin -> byteArrayOf(PLUGIN) + text(holder.name)
        is HolderId.Custom -> byteArrayOf(CUSTOM) + text(holder.kind) + text(holder.id)
    }

    fun decodeHolder(v: MemorySegment): HolderId = when (val tag = v.i8(0)) {
        BLOCK -> HolderId.Block(WorldId(At.world[v]), At.x[v], At.y[v], At.z[v])
        PLACED_BLOCK -> HolderId.PlacedBlock(WorldId(At.world[v]), At.x[v], At.y[v], At.z[v])
        PLAYER -> HolderId.Player(Id.uuid[v])
        ENDER_CHEST -> HolderId.PlayerStash(Id.uuid[v])
        ENTITY -> HolderId.Entity(Id.uuid[v])
        PLACED_ENTITY -> HolderId.PlacedEntity(Id.uuid[v])
        ITEM_ENTITY -> HolderId.ItemEntity(Id.uuid[v])
        ESCROW -> HolderId.Escrow(RollbackJobId(Escrow.job[v]))
        SOURCE -> HolderId.Source(SourceKind.entries[v.i8(1).toInt()])
        SINK -> HolderId.Sink(SinkKind.entries[v.i8(1).toInt()])
        PLUGIN -> HolderId.Plugin(text(v, 1))
        CUSTOM -> HolderId.Custom(text(v, 1), text(v, 3L + length(v, 1)))
        else -> error("unrecognized holder tag: $tag.")
    }

    fun itemKey(itemKey: ItemKey): ByteArray = text(itemKey.material) + text(itemKey.decoration?.hex ?: "")

    fun decodeItemKey(v: MemorySegment): ItemKey {
        val material = text(v, 0)
        val decoration = text(v, 2L + length(v, 0))
        return ItemKey(material, decoration.ifEmpty { null }?.let(::ContentHash))
    }

    fun blockData(blockData: BlockDataKey): ByteArray = text(blockData.value)
    fun decodeBlockData(v: MemorySegment): BlockDataKey = BlockDataKey(text(v, 0))

    fun entityType(entityType: EntityTypeKey): ByteArray = text(entityType.value)
    fun decodeEntityType(v: MemorySegment): EntityTypeKey = EntityTypeKey(text(v, 0))

    fun world(world: WorldId): ByteArray = WorldAt.build { WorldAt.uuid(world.uuid) }
    fun decodeWorld(v: MemorySegment): WorldId = WorldId(WorldAt.uuid[v])

    private fun at(tag: Byte, world: WorldId, x: Int, y: Int, z: Int) =
        At.build { At.tag(tag); At.world(world.uuid); At.x(x); At.y(y); At.z(z) }

    private fun id(tag: Byte, uuid: UUID) = Id.build { Id.tag(tag); Id.uuid(uuid) }

    private fun text(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        return ByteArray(2 + utf8.size).also {
            it[0] = (utf8.size ushr 8).toByte()
            it[1] = utf8.size.toByte()
            utf8.copyInto(it, 2)
        }
    }

    private fun length(v: MemorySegment, at: Long): Int =
        ((v.i8(at).toInt() and 0xFF) shl 8) or (v.i8(at + 1).toInt() and 0xFF)

    private fun text(v: MemorySegment, at: Long): String = String(v.readBytes(at + 2, length(v, at)), Charsets.UTF_8)
}
