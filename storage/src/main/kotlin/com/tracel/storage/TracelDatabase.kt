package com.tracel.storage

import com.tracel.storage.schema.migrateSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One `SQLite` file, opened through a single-connection pool: `SQLite` allows only one writer at
 * a time, so a bigger pool would just be connections queueing behind each other for nothing.
 * [Storage] enforces the same constraint one level up, where a caller waiting for its turn
 * suspends instead of pinning a thread.
 *
 * The database owns its writer thread, and hands it out as [dispatcher] — the same thread
 * `TracelSchedulers.storage` reports, because two different "the storage thread"s would be one
 * thread too many.
 */
class TracelDatabase private constructor(
    val exposed: Database,
    val storage: Storage,
    private val dataSource: HikariDataSource,
    private val executor: ExecutorService,
) : AutoCloseable {
    /** The single dedicated `SQLite`-writer thread. */
    val dispatcher: CoroutineDispatcher get() = storage.dispatcher

    override fun close() {
        dataSource.close()
        executor.shutdown()
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
                        statement.execute("PRAGMA synchronous=NORMAL")
                        statement.execute("PRAGMA busy_timeout=5000")
                        statement.execute("PRAGMA temp_store=MEMORY")
                        statement.execute("PRAGMA cache_size=-16384")
                        statement.execute("PRAGMA mmap_size=268435456")
                    }
                },
            )
            migrateSchema(exposed)
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Tracel-Storage") }
            return TracelDatabase(exposed, Storage(exposed, executor.asCoroutineDispatcher()), dataSource, executor)
        }
    }
}
