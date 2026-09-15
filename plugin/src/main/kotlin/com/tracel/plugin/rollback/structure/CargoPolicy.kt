package com.tracel.plugin.rollback.structure

import java.util.UUID

/**
 * Whose cargo a hull carries during one structure pass.
 *
 * Only composer knows; structure obeys.
 */
data class CargoPolicy(
    val keepCargoFor: Set<UUID> = emptySet(),
    val ledgerCargoFor: Set<UUID> = emptySet(),
    val ledgerHeldBy: Set<UUID> = emptySet(),
) {
    companion object {
        val NONE: CargoPolicy = CargoPolicy()
    }
}
