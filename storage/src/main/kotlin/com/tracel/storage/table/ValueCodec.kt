package com.tracel.storage.table

import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Scalar
import com.tracel.storage.ffm.Bytes.i32
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/** What a table's rows hold. [read] answers `null` for a row that does not hold a [V]. */
interface ValueCodec<V> {
    fun write(storage: TracelStorage, unit: StorageUnit, value: V): ByteArray

    fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment): V?

    companion object {
        val none: ValueCodec<Unit> = object : ValueCodec<Unit> {
            private val empty = ByteArray(0)

            override fun write(storage: TracelStorage, unit: StorageUnit, value: Unit): ByteArray = empty

            override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment) = Unit
        }

        val int: ValueCodec<Int> = object : ValueCodec<Int> {
            override fun write(storage: TracelStorage, unit: StorageUnit, value: Int): ByteArray = Scalar.int(value)

            override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment): Int =
                Scalar.asInt(value)
        }

        val bytes: ValueCodec<ByteArray> = object : ValueCodec<ByteArray> {
            override fun write(storage: TracelStorage, unit: StorageUnit, value: ByteArray): ByteArray = value

            override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment): ByteArray =
                value.toArray(JAVA_BYTE)
        }

        inline fun <reified H : HolderId> holder(): ValueCodec<H> = object : ValueCodec<H> {
            override fun write(storage: TracelStorage, unit: StorageUnit, value: H): ByteArray =
                Scalar.int(storage.interning.internHolder(unit, value))

            override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment): H? =
                storage.interning.resolveHolder(unit, value.i32(0)) as? H
        }
    }
}
