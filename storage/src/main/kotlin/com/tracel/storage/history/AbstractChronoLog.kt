package com.tracel.storage.history

import com.tracel.storage.Interning
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Chrono

/**
 * A history kept by place: its rows live in [Chrono]'s tile capsules,
 * and every ID in them goes through the store's interning.
 *
 * What a log adds is what it writes and how it is asked.
 */
abstract class AbstractChronoLog(protected val storage: TracelStorage, protected val chrono: Chrono) {
    protected val interning: Interning get() = storage.interning
}
