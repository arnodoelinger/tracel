package com.tracel.plugin.rollback.structure.block

import com.tracel.engine.rollback.structure.StructureStep

/** Outcome. */
internal sealed interface Outcome

/** The write went through. */
internal data class Applied(val step: StructureStep.SetBlock, val differed: Boolean = false) : Outcome

/** The write was skipped outright — nothing landed, [reason] says why. */
internal data class Refused(val reason: String) : Outcome
