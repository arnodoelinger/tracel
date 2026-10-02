package com.tracel.plugin.rollback.structure

import java.util.*

/**
 * How one structure pass writes.
 *
 * Defaults are what undo wants; forward apply spells every knob out.
 */
data class StructurePass(
    val force: Boolean = true,
    val dumpHeldCargo: Boolean = force,
    val driftOnly: Boolean = false,
    val keepCargoFor: Set<UUID> = emptySet(),
    val ledgerCargoFor: Set<UUID> = emptySet(),
    val ledgerHeldBy: Set<UUID> = emptySet(),
)
