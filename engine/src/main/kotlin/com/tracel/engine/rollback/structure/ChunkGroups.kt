package com.tracel.engine.rollback.structure

import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos

/**
 * Splits [items] by the chunk [at] puts each one in, in the order the chunks are first met. Items keep their order
 * inside a group.
 *
 * A plan comes sorted by chunk, so most items continue the group before them and cost no lookup; a map is only asked
 * when the chunk changes. Grouping with a `Triple` of world and chunk as the key allocated one per item.
 */
public fun <T> groupByChunk(items: List<T>, at: (T) -> BlockPos): List<List<T>> {
    val groups = ArrayList<ArrayList<T>>()
    val byWorld = HashMap<WorldId, HashMap<Long, ArrayList<T>>>()
    var lastWorld: WorldId? = null
    var lastKey = 0L
    var last: ArrayList<T>? = null
    for (item in items) {
        val pos = at(item)
        val key = ((pos.x shr 4).toLong() shl 32) or ((pos.z shr 4).toLong() and 0xffffffffL)
        val current = last
        if (current != null && key == lastKey && pos.world == lastWorld) {
            current += item
            continue
        }
        val group = byWorld.getOrPut(pos.world) { HashMap() }.getOrPut(key) { ArrayList<T>().also { groups += it } }
        group += item
        lastWorld = pos.world
        lastKey = key
        last = group
    }
    return groups
}
