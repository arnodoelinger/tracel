package com.tracel.plugin.startup

import com.tracel.plugin.services.TracelServices
import com.tracel.storage.ports.ops.StoreAdmin
import kotlinx.coroutines.*

/** Wired plugin after a successful [enable]. */
internal class TracelRuntime(
    val store: StoreAdmin,
    val services: TracelServices,
    val drain: Job,
    val entityDrain: Job,
    val formDrain: Job,
    val releaseDrain: Job,
    val lastCaptures: suspend () -> Unit,
)
