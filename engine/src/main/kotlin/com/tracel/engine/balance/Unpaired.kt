package com.tracel.engine.balance

import com.tracel.model.holder.HolderId

/** One side of a gain or loss still waiting to be matched against the other side of the same item. */
internal data class Unpaired(val holder: HolderId, val amount: Long, val fromGap: Boolean)
