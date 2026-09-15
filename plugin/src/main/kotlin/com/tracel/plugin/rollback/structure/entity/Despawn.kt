package com.tracel.plugin.rollback.structure.entity

import java.util.UUID

/** Despawn status. */
internal sealed interface Despawn {
    /** Despawn. */
    data class Removed(val uuid: UUID) : Despawn

    /** Nothing to despawn. */
    data object Absent : Despawn

    /** Refure to despawn. */
    data class Refused(val reason: String) : Despawn
}
