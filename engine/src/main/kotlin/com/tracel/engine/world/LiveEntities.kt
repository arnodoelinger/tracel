package com.tracel.engine.world

import com.tracel.engine.store.KeySet
import java.util.UUID

/** Which booked entities are still in the world. */
public typealias LiveEntities = KeySet<UUID>
