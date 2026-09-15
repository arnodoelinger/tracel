package com.tracel.plugin.rollback.result.report

import com.tracel.engine.rollback.structure.StructureStep

/**
 * Outcome of a structural restore.
 *
 * [applied] is as performed (live world state), not as planned.
 */
data class StructureReport(
    val applied: List<StructureStep>,
    val skipped: List<SkippedStep>,
    val overwritten: Int = 0,
) {
    val count: Int get() = applied.size

    val fullyRestored: Boolean get() = skipped.isEmpty()

    companion object {
        val EMPTY: StructureReport = StructureReport(emptyList(), emptyList())
    }

    /** Full structure report. */
    operator fun plus(other: StructureReport): StructureReport =
        StructureReport(applied + other.applied, skipped + other.skipped, overwritten + other.overwritten)
}
