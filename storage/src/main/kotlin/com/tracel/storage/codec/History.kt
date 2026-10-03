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
        Keys.WCHG, Keys.WCHG_AT, Keys.WCHG_AT_SECTION, Keys.WCHG_ENTITY -> BLOCKS
        Keys.TXN, Keys.TXN_BY_ID, Keys.ITEM, Keys.TXN_LOT -> ITEMS
        Keys.CONTAINER_SLOT, Keys.ACTOR_VISIT -> CONTAINERS
        Keys.EVENT, Keys.EVENT_ACTOR, Keys.EVENT_TIME -> EVENTS
        Keys.ACTOR, Keys.TIME, Keys.SPATIAL -> when (valueFirst) {
            LogKind.WORLD.ordinal -> BLOCKS
            LogKind.TRANSACTION.ordinal -> ITEMS
            else -> SegmentClassifier.STATE
        }

        else -> SegmentClassifier.STATE
    }

    /** The sequence number the record behind this row has, or `-1` for a row that is not tied to one. */
    fun seqOf(key: ByteArray, value: MemorySegment?): Long = when (key[0]) {
        Keys.WCHG, Keys.TXN, Keys.TXN_LOT, Keys.EVENT -> KeyReader.u64(key, 1)
        Keys.TXN_BY_ID -> value?.let(Records::asLong) ?: -1L
        Keys.WCHG_AT, Keys.WCHG_AT_SECTION, Keys.WCHG_ENTITY,
        Keys.ACTOR, Keys.ITEM, Keys.TIME, Keys.SPATIAL, Keys.EVENT_ACTOR, Keys.EVENT_TIME,
            -> Keys.invert(KeyReader.u64(key, key.size - 8))

        else -> -1L
    }

    /** The settings an engine needs to keep history in windows. */
    fun configured(config: LsmConfig): LsmConfig =
        config.copy(classifier = classifier)
}
