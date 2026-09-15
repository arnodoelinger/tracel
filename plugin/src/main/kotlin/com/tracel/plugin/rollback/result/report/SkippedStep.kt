package com.tracel.plugin.rollback.result.report

import com.tracel.model.world.BlockPos

/** Skipped step of rollback. */
data class SkippedStep(val at: BlockPos, val reason: String)
