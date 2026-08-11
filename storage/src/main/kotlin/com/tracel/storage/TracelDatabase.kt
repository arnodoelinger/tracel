package com.tracel.storage

import com.tracel.storage.schema.migrateSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path

/**
 * One `SQLite` file, opened through a single-connection pool: `SQLite` allows only one writer at
 * a time, so a bigger pool would just be connections queueing behind each other for nothing.
 * Pairs with [com.tracel.engine.ownership.SingleWriterGuard], which enforces the same
 * constraint at the call site rather than the connection pool.
 *
 * WAL mode trades a second file on disk (`-wal`) for meaningfully better crash behavior than
 * `SQLite`'s default rollback-journal mode — worth it for a database whose entire purpose is
 * surviving the process dying mid-write.
 */
class TracelDatabase private constructor(
    val exposed: Database,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    override fun close() {
        dataSource.close()
    }

    companion object {
        fun open(path: Path): TracelDatabase {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite:$path"
                    driverClassName = "org.sqlite.JDBC"
                    maximumPoolSize = 1
                }
            )
            val exposed = Database.connect(
                dataSource,
                setupConnection = { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA journal_mode=WAL")
                        statement.execute("PRAGMA foreign_keys=ON")
                    }
                },
            )
            migrateSchema(exposed)
            return TracelDatabase(exposed, dataSource)
        }
    }
}
