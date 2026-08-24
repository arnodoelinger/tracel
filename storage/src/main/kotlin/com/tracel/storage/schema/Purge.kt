package com.tracel.storage.schema

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
fun purgeAll(db: Database) {
    transaction(db) {
        for (table in ALL_TABLES) table.deleteAll()
    }
}
