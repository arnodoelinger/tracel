package com.tracel.engine.actor

import com.tracel.model.world.entity.EntityTypeKey
import java.util.*

/** What a mob is, as far as the server can tell right now. */
public fun interface EntityKindSource {
    /** @return the type of the live (or just gone) entity [uuid], or `null` if it is not known. */
    public fun kindOf(uuid: UUID): EntityTypeKey?
}
