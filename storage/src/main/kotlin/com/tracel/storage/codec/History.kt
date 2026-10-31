package com.tracel.storage.codec

import com.tracel.model.log.LogKind
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.SegmentClassifier
import java.lang.foreign.MemorySegment

/**
 * Which kind of history a row is, so the engine files it in a window of its own and
 * a purge can throw the window away.
 */
object History {
    const val BLOCKS = 1
    const val ITEMS = 2
    const val CONTAINERS = 3
    const val EVENTS = 4

    val classifier = SegmentClassifier { tag, valueFirst -> categoryOf(tag, valueFirst) }

    /** The kind of history a row with this [tag] and this first byte of value is, or [SegmentClassifier.STATE]. */
    fun categoryOf(tag: Int, valueFirst: Int): Int = when (tag.toByte()) {
        Tag.WCHG.byte, Tag.WCHG_AT.byte, Tag.WCHG_AT_SECTION.byte, Tag.WCHG_ENTITY.byte -> BLOCKS
        Tag.TXN.byte, Tag.TXN_BY_ID.byte, Tag.ITEM.byte, Tag.TXN_LOT.byte -> ITEMS
        Tag.CONTAINER_SLOT.byte, Tag.ACTOR_VISIT.byte -> CONTAINERS
        Tag.EVENT.byte, Tag.EVENT_ACTOR.byte, Tag.EVENT_TIME.byte, Tag.EVENT_ENTITY.byte -> EVENTS
        Tag.ACTOR.byte, Tag.TIME.byte, Tag.SPATIAL.byte -> when (valueFirst) {
            LogKind.WORLD.ordinal -> BLOCKS
            LogKind.TRANSACTION.ordinal -> ITEMS
            else -> SegmentClassifier.STATE
        }

        else -> SegmentClassifier.STATE
    }

    /** The sequence number the record behind this row has, or `-1` for a row that is not tied to one. */
    fun seqOf(key: ByteArray, value: MemorySegment?): Long = when (key[0]) {
        Tag.WCHG.byte, Tag.TXN.byte, Tag.TXN_LOT.byte, Tag.EVENT.byte -> KeyReader.u64(key, 1)
        Tag.TXN_BY_ID.byte -> value?.let(Records::asLong) ?: -1L
        Tag.WCHG_AT.byte, Tag.WCHG_AT_SECTION.byte, Tag.WCHG_ENTITY.byte,
        Tag.ACTOR.byte, Tag.ITEM.byte, Tag.TIME.byte, Tag.SPATIAL.byte, Tag.EVENT_ACTOR.byte, Tag.EVENT_TIME.byte,
        Tag.EVENT_ENTITY.byte,
            -> Keys.invert(KeyReader.u64(key, key.size - 8))

        else -> -1L
    }

    /**
     * Whether rows with this [tag] are ever read by exact key. The ones named here are indexes, only ever walked by
     * prefix, and they are half the rows of a store: a filter over them is bytes nobody reads.
     */
    fun pointRead(tag: Int): Boolean = when (tag.toByte()) {
        Tag.ACTOR.byte, Tag.ITEM.byte, Tag.TIME.byte, Tag.SPATIAL.byte, Tag.TXN_LOT.byte,
        Tag.WCHG_AT.byte, Tag.WCHG_AT_SECTION.byte, Tag.WCHG_ENTITY.byte,
        Tag.EVENT_ACTOR.byte, Tag.EVENT_TIME.byte, Tag.EVENT_ENTITY.byte, Tag.ROLLED_JOB.byte, Tag.ROLLED_SET_JOB.byte,
            -> false

        else -> true
    }

    /** The settings an engine needs to keep history in windows and index rows out of the filters. */
    fun configured(config: LsmConfig): LsmConfig =
        config.copy(classifier = classifier, pointRead = ::pointRead)
}
