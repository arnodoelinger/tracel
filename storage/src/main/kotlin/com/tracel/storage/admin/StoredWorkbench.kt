package com.tracel.storage.admin

import com.tracel.engine.store.StoreWorkbench
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Chrono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [StoreWorkbench] over the store itself. */
class StoredWorkbench(private val storage: TracelStorage, private val chrono: Chrono) : StoreWorkbench {
    override suspend fun press() = chrono.settle()

    override suspend fun compact() = withContext(Dispatchers.IO) {
        storage.engine.flush()
        storage.engine.compactEverything()
    }
}
