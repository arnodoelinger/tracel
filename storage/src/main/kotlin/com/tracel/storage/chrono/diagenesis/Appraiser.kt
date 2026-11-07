package com.tracel.storage.chrono.diagenesis

/** Weighs a tile's waiting fragments against the bytes a merge would rewrite. */
internal class Appraiser(private val settings: DiagenesisSettings) {
    fun score(entry: Ledger.Entry, tailBytes: Long, reads: Int, now: Long): Double {
        if (entry.holdUntil > now) return 0.0
        val age = now - entry.since
        if (entry.fragments >= settings.hardFragments || age >= settings.hardAgeMillis) return Double.MAX_VALUE
        val weight = 1.0 + minOf(reads, MAX_READ_WEIGHT)
        val benefit = ((entry.fragments - 1) * weight + age.toDouble() / settings.lingerMillis) *
                (if (entry.urgent) URGENT_FACTOR else 1.0)
        val rewritten = entry.bytes + minOf(tailBytes, settings.tier * entry.bytes)
        val cost = 1.0 + rewritten / KIB
        return benefit / (cost * settings.payoff)
    }

    private companion object {
        const val MAX_READ_WEIGHT = 8
        const val URGENT_FACTOR = 4.0
        const val KIB = 1024.0
    }
}
