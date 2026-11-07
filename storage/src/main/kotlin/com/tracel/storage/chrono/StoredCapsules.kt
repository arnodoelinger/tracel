package com.tracel.storage.chrono

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.chrono.query.CapsuleSource
import com.tracel.storage.codec.Tag

/** Capsules as the store keeps them, merged and not yet merged, read through one unit's snapshot. */
internal class StoredCapsules(private val unit: StorageUnit) : CapsuleSource {
    override fun tile(tile: Tile, lane: Lane, visit: (CapsuleView) -> Boolean) {
        unit.scan(ChronoKeys.fragments(tile, lane)).use { fragments ->
            unit.scan(ChronoKeys.capsules(tile, lane)).use { capsules ->
                var haveFragment = fragments.next()
                var haveCapsule = capsules.next()
                while (haveFragment || haveCapsule) {
                    val fragmentLast = if (haveFragment) Tag.CHRONO_FRAGMENT.long(fragments, 4) else Long.MIN_VALUE
                    val capsuleLast = if (haveCapsule) Tag.CHRONO_CAPSULE.long(capsules, 4) else Long.MIN_VALUE
                    if (haveFragment && (!haveCapsule || fragmentLast >= capsuleLast)) {
                        if (!visit(lane.open(fragments.value()))) return
                        haveFragment = fragments.next()
                    } else {
                        if (!visit(lane.open(capsules.value()))) return
                        haveCapsule = capsules.next()
                    }
                }
            }
        }
    }

    override fun actor(actor: Int, lane: Lane, visit: (Tile, CapsuleView) -> Boolean) {
        val fragments = allFragments(lane).filter { actor in it.second.trackIds }
        walk(fragments, visit) { emit ->
            unit.scan(Tag.CHRONO_TRACK[actor]).use { cursor ->
                while (cursor.next()) {
                    if (Tag.CHRONO_TRACK.u8(cursor, 5) != lane.id) continue
                    val last = Tag.CHRONO_TRACK.long(cursor, 1)
                    val tile = Tile.of(
                        Tag.CHRONO_TRACK.int(cursor, 2),
                        Tag.CHRONO_TRACK.int(cursor, 3),
                        Tag.CHRONO_TRACK.int(cursor, 4)
                    )
                    val value = unit.get(ChronoKeys.capsule(tile, lane, last)) ?: continue
                    if (!emit(tile, lane.open(value))) return@walk
                }
            }
        }
    }

    override fun recent(lane: Lane, visit: (Tile, CapsuleView) -> Boolean) {
        walk(allFragments(lane), visit) { emit ->
            unit.scan(Tag.CHRONO_TIME.prefix).use { cursor ->
                while (cursor.next()) {
                    if (Tag.CHRONO_TIME.u8(cursor, 4) != lane.id) continue
                    val last = Tag.CHRONO_TIME.long(cursor, 0)
                    val tile = Tile.of(
                        Tag.CHRONO_TIME.int(cursor, 1),
                        Tag.CHRONO_TIME.int(cursor, 2),
                        Tag.CHRONO_TIME.int(cursor, 3)
                    )
                    val value = unit.get(ChronoKeys.capsule(tile, lane, last)) ?: continue
                    if (!emit(tile, lane.open(value))) return@walk
                }
            }
        }
    }

    /** Every fragment on [lane], anywhere, newest first. */
    fun allFragments(lane: Lane): List<Pair<Tile, CapsuleView>> {
        val out = ArrayList<Pair<Tile, CapsuleView>>()
        unit.scan(Tag.CHRONO_FRAGMENT.prefix).use { cursor ->
            while (cursor.next()) {
                if (Tag.CHRONO_FRAGMENT.u8(cursor, 3) != lane.id) continue
                val tile = Tile.of(
                    Tag.CHRONO_FRAGMENT.int(cursor, 0),
                    Tag.CHRONO_FRAGMENT.int(cursor, 1),
                    Tag.CHRONO_FRAGMENT.int(cursor, 2)
                )
                out += tile to lane.open(cursor.value())
            }
        }
        out.sortByDescending { it.second.lastSeq }
        return out
    }

    private inline fun walk(
        fragments: List<Pair<Tile, CapsuleView>>,
        crossinline visit: (Tile, CapsuleView) -> Boolean,
        capsules: (emit: (Tile, CapsuleView) -> Boolean) -> Unit,
    ) {
        var f = 0
        var stopped = false
        capsules { tile, capsule ->
            while (f < fragments.size && fragments[f].second.lastSeq > capsule.lastSeq) {
                val (ft, fc) = fragments[f++]
                if (!visit(ft, fc)) {
                    stopped = true
                    return@capsules false
                }
            }
            if (!visit(tile, capsule)) {
                stopped = true
                false
            } else {
                true
            }
        }
        if (stopped) return
        while (f < fragments.size) {
            val (ft, fc) = fragments[f++]
            if (!visit(ft, fc)) return
        }
    }
}
