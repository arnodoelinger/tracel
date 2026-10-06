package com.tracel.plugin.adapter.block.capability.cargo

import org.bukkit.block.data.BlockData

/** Strip every occupancy flag `Paper` currently exposes. */
internal object CargoClaims {
    private val all = listOf(OccupiedSlotsClaim, HasRecordClaim, HasBookClaim, HasBottleClaim)

    /** Clone first. */
    fun cleared(data: BlockData): String {
        val clone = data.clone()
        for (claim in all) claim.clear(clone)
        return clone.asString
    }
}
