@file:Suppress("DEPRECATION")

package com.tracel.storage.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Which schema versions a database file has already had applied. */
private object SchemaVersionTable : Table("schema_version") {
    val version = integer("version")
    override val primaryKey = PrimaryKey(version)
}

/**
 * One forward-only schema change, applied at most once per database file.
 * There are no down-migrations: a rollback is a new migration, the same rule the ledger itself follows for data.
 */
private class Migration(val version: Int, val apply: () -> Unit)

/** All migrations, in order of increasing version. */
private val migrations = listOf(
    Migration(1) { // TODO: remove all migrations in future
        SchemaUtils.createMissingTablesAndColumns(
            ItemKeysTable,
            HoldersTable,
            LotsTable,
            LotEdgesTable,
            PlacementsTable,
            JournalProgressTable,
            TransactionsTable,
            FlowsTable,
            LotLeasesTable,
        )
    },
    Migration(2) {
        SchemaUtils.createMissingTablesAndColumns(CountersTable)
    },
    Migration(3) {
        SchemaUtils.createMissingTablesAndColumns(
            RollbackJobsTable,
            RollbackStepsTable,
            RollbackStepInputsTable,
            InvolutionProgressTable,
        )
    },
    Migration(4) {
        SchemaUtils.createMissingTablesAndColumns(PendingDeliveriesTable)
    },
)

/** Brings [db]'s schema up to the latest version, applying only whatever migrations it is still missing. */
fun migrateSchema(db: Database) {
    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(SchemaVersionTable)
        val applied = SchemaVersionTable.selectAll().map { it[SchemaVersionTable.version] }.toSet()
        for (migration in migrations.sortedBy { it.version }) {
            if (migration.version in applied) continue
            migration.apply()
            SchemaVersionTable.insert { it[version] = migration.version }
        }
    }
}
