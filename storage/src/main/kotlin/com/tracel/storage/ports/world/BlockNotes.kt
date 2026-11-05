package com.tracel.storage.ports.world

import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Scalar
import com.tracel.storage.util.eachRow
import com.tracel.engine.world.BlockNotes as BlockNotesPort

/** One row per block that has a note, keyed by where it stands. */
class BlockNotes(private val storage: TracelStorage) : BlockNotesPort {
    override fun loadAll(): Map<BlockPos, Int> =
        StorageUnit.open(storage.engine).use { unit ->
            val out = HashMap<BlockPos, Int>()
            unit.eachRow(Tag.BLOCK_NOTE.prefix) { cursor ->
                val world = storage.interning.resolveWorld(unit, Tag.BLOCK_NOTE.int(cursor, 0))
                val at = BlockPos(
                    world,
                    Tag.BLOCK_NOTE.int(cursor, 1),
                    Tag.BLOCK_NOTE.int(cursor, 2),
                    Tag.BLOCK_NOTE.int(cursor, 3),
                )
                out[at] = Scalar.asInt(cursor.value())
            }
            out
        }

    override suspend fun writeAll(notes: Map<BlockPos, Int?>) {
        if (notes.isEmpty()) return
        storage.write {
            for ((at, note) in notes) {
                if (note == null) {
                    val world = storage.interning.findWorldId(this, at.world) ?: continue
                    delete(Tag.BLOCK_NOTE[world, at.x, at.y, at.z])
                } else {
                    val world = storage.interning.internWorld(this, at.world)
                    put(Tag.BLOCK_NOTE[world, at.x, at.y, at.z], Scalar.int(note))
                }
            }
        }
    }
}
