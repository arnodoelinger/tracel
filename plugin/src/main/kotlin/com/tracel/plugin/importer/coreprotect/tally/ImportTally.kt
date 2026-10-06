package com.tracel.plugin.importer.coreprotect.tally

import java.util.*

/** What came out of the rows so far. */
class ImportTally {
    var rows: Long = 0

    var folded: Long = 0
    var oldest: Long = Long.MAX_VALUE
    var newest: Long = Long.MIN_VALUE
    val taken: EnumMap<Taken, Long> = EnumMap(Taken::class.java)
    val skipped: EnumMap<Skipped, Long> = EnumMap(Skipped::class.java)
    val missingWorlds: MutableSet<String> = LinkedHashSet()

    /** Records that a row was left behind for [why] */
    internal fun skip(why: Skipped) {
        skipped.merge(why, 1L, Long::plus)
    }

    /** Records that a row of [what] was taken at [millis]. */
    internal fun took(what: Taken, millis: Long) {
        taken.merge(what, 1L, Long::plus)
        if (millis < oldest) oldest = millis
        if (millis > newest) newest = millis
    }
}
