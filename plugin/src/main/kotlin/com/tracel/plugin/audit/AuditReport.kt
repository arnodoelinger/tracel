package com.tracel.plugin.audit

import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/** One item key where the live world and the ledger disagree on quantity. */
data class Drift(val itemKey: ItemKey, val live: Long, val believed: Long)

/**
 * Compares a live-scanned holder against what the ledger believes it
 * holds ([believed], [com.tracel.engine.ledger.LotLedger.totalsAt]).
 *
 * Missing on either side counts as zero, so an item the world has but
 * the ledger has never heard of (or vice versa) shows up exactly like any
 * other mismatch.
 */
fun diffTotals(live: Map<ItemKey, Long>, believed: Map<ItemKey, Quantity>): List<Drift> =
    (live.keys + believed.keys).distinct().mapNotNull { key ->
        val liveQty = live[key] ?: 0L
        val believedQty = believed[key]?.raw ?: 0L
        if (liveQty != believedQty) Drift(key, liveQty, believedQty) else null
    }
