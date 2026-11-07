package com.tracel.storage.chrono

import com.tracel.storage.chrono.capsule.Capsule
import com.tracel.storage.chrono.capsule.CapsuleEncoder
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.chrono.capsule.EntityCapsule
import com.tracel.storage.chrono.capsule.EntityCapsuleEncoder
import com.tracel.storage.chrono.capsule.ItemsCapsule
import com.tracel.storage.chrono.capsule.ItemsCapsuleEncoder
import java.lang.foreign.MemorySegment

/** A kind of history a tile keeps, each in capsules of its own format. */
enum class Lane(val id: Int, val magic: Int) {
    BLOCKS(0, 0xC7) {
        override fun open(bytes: MemorySegment): CapsuleView = Capsule(bytes)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            blocks.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    ENTITIES(1, 0xC8) {
        override fun open(bytes: MemorySegment): CapsuleView = EntityCapsule(bytes, ENTITIES)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            entities.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    EVENTS(2, 0xC9) {
        override fun open(bytes: MemorySegment): CapsuleView = EntityCapsule(bytes, EVENTS)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            events.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    ITEMS(3, 0xCA) {
        override fun open(bytes: MemorySegment): CapsuleView = ItemsCapsule(bytes)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            items.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    CONTAINERS(4, 0xCB) {
        override fun open(bytes: MemorySegment): CapsuleView = EntityCapsule(bytes, CONTAINERS)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            containers.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    VISITS(5, 0xCC) {
        override fun open(bytes: MemorySegment): CapsuleView = EntityCapsule(bytes, VISITS)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            visits.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    },
    MODES(6, 0xCD) {
        override fun open(bytes: MemorySegment): CapsuleView = EntityCapsule(bytes, MODES)
        override fun encode(rows: Rows, window: Long, bounds: CapsuleView?): ByteArray =
            modes.get().encode(rows, window, bounds?.lastSeq ?: Long.MIN_VALUE, bounds?.maxMillis ?: Long.MIN_VALUE)
    };

    /** Reads a capsule of this lane in place. */
    abstract fun open(bytes: MemorySegment): CapsuleView

    /** Encodes [rows], all of this lane and all of [window], as one capsule. */
    abstract fun encode(rows: Rows, window: Long, bounds: CapsuleView? = null): ByteArray

    companion object {
        private val blocks = ThreadLocal.withInitial { CapsuleEncoder() }
        private val entities = ThreadLocal.withInitial { EntityCapsuleEncoder(ENTITIES.magic) }
        private val events = ThreadLocal.withInitial { EntityCapsuleEncoder(EVENTS.magic) }
        private val items = ThreadLocal.withInitial { ItemsCapsuleEncoder() }
        private val containers = ThreadLocal.withInitial { EntityCapsuleEncoder(CONTAINERS.magic) }
        private val visits = ThreadLocal.withInitial { EntityCapsuleEncoder(VISITS.magic) }
        private val modes = ThreadLocal.withInitial { EntityCapsuleEncoder(MODES.magic) }

        private val BY_ID = entries.toTypedArray()

        val WORLD: List<Lane> = listOf(BLOCKS, ENTITIES)

        fun ofMagic(magic: Int): Lane? = BY_ID.firstOrNull { it.magic == magic }

        fun of(id: Int): Lane = BY_ID.getOrNull(id) ?: error("no lane $id.")
    }
}
