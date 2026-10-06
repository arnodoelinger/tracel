package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/** What each key's takes fell short by, as the holders reported it. A holder that says nothing itemised took nothing. */
internal fun shortfallOf(
    takes: Map<HolderId, Map<ItemKey, Long>>,
    results: List<Pair<HolderId, ApplyResult>>,
): Map<ItemKey, Long> {
    val out = LinkedHashMap<ItemKey, Long>()
    for ((holder, result) in results) {
        val failed = result as? ApplyResult.Failed ?: continue
        val asked = takes[holder] ?: continue
        val short = failed.short ?: asked.mapValues { -it.value }
        for ((key, amount) in short) {
            val owed = -(asked[key] ?: continue)
            if (amount > 0L) out.merge(key, minOf(amount, owed), Long::plus)
        }
    }
    return out
}

/** Gives after the takes that funded them fell short: [funded] goes out, [withheld] does not, [unspent] found no give. */
internal class Unfunded(
    val funded: Map<HolderId, Map<ItemKey, Long>>,
    val withheld: Map<HolderId, Map<ItemKey, Long>>,
    val unspent: Map<ItemKey, Long>,
)

/**
 * Cuts [short] out of [gives]: piles first, since a pile that is not respawned costs nothing else, then the rest.
 * Holders that are not a place items physically sit in take no part.
 */
internal fun withholdUnfunded(gives: Map<HolderId, Map<ItemKey, Long>>, short: Map<ItemKey, Long>): Unfunded {
    if (short.isEmpty()) return Unfunded(gives, emptyMap(), emptyMap())
    val left = LinkedHashMap(short)
    val funded = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    val withheld = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    fun order(holder: HolderId): Int = when (holder) {
        is HolderId.ItemEntity -> 0
        is HolderId.Entity -> 1
        is HolderId.Block -> 2
        is HolderId.PlayerStash -> 3
        is HolderId.Player -> 4
        else -> 5
    }
    for ((holder, itemDeltas) in gives.entries.sortedBy { order(it.key) }) {
        if (order(holder) == 5) {
            funded[holder] = itemDeltas
            continue
        }
        val kept = LinkedHashMap<ItemKey, Long>()
        val cut = LinkedHashMap<ItemKey, Long>()
        for ((key, amount) in itemDeltas) {
            val owed = left[key] ?: 0L
            val take = minOf(owed, amount)
            if (take > 0L) {
                left[key] = owed - take
                cut[key] = take
            }
            if (amount > take) kept[key] = amount - take
        }
        if (kept.isNotEmpty()) funded[holder] = kept
        if (cut.isNotEmpty()) withheld[holder] = cut
    }
    return Unfunded(funded, withheld, left.filterValues { it > 0L })
}
