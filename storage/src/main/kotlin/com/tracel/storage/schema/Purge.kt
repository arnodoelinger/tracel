package com.tracel.storage.schema

import com.tracel.storage.Storage
import com.tracel.storage.intern.Interning
import org.jetbrains.exposed.v1.jdbc.deleteAll

/** Every table `Tracel` owns. */
private val ALL_TABLES = listOf(
    FlowsTable,
    TransactionsTable,
    LotEdgesTable,
    PlacementsTable,
    LotsTable,
    ItemKeysTable,
    HoldersTable,
    JournalProgressTable,
    LotLeasesTable,
    RollbackStepInputsTable,
    RollbackStepsTable,
    RollbackJobsTable,
    InvolutionProgressTable,
    PendingDeliveriesTable,
    CountersTable,
)

/** Deletes every row `Tracel` has ever written. */
suspend fun purgeAll(storage: Storage) {
    storage.write {
        for (table in ALL_TABLES) table.deleteAll()
    }

    // Clear cache
    Interning.forget(storage.exposed)
}
