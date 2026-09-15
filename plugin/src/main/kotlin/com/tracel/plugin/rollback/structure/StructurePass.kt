package com.tracel.plugin.rollback.structure

/**
 * How one structure pass writes.
 *
 * Defaults are what undo wants; forward apply spells every knob out.
 */
data class StructurePass(
    val force: Boolean = true,
    val phase: StructurePhase = StructurePhase.BLOCKS,
    val drain: Boolean = false,
    val dumpHeldCargo: Boolean = force,
)
