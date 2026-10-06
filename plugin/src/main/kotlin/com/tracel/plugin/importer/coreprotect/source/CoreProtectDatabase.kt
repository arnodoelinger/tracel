package com.tracel.plugin.importer.coreprotect.source

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*

/** A `CoreProtect` database. */
class CoreProtectDatabase private constructor(
    private val connection: Connection,
    private val prefix: String,
) : AutoCloseable {
    private val columns = SourceTable.entries.associateWith { columns(it.suffix) }
    private val userId = if ("id" in columns("user")) "id" else "rowid"
    private val skullId = if ("id" in columns("skull")) "id" else "rowid"
    private val entityId = if ("id" in columns("entity")) "id" else "rowid"
    private val skulls = HashMap<Int, SkullRow?>()

    /** The newest version this database was written by, or `null` for one too old to say. */
    fun version(): String? = runCatching {
        one("SELECT version FROM ${prefix}version ORDER BY time DESC LIMIT 1") { it.getString(1) }
    }.getOrNull()

    /**
     * What tells this database from another server's: when it was made and who was first on it. A copy of the
     * file is the same database and gets the same answer.
     */
    fun fingerprint(): Long {
        val made =
            runCatching { one("SELECT time FROM ${prefix}version ORDER BY time LIMIT 1") { it.getLong(1) } }.getOrNull()
        val first =
            one("SELECT time, user FROM ${prefix}user ORDER BY $userId LIMIT 1") { "${it.getLong(1)}:${it.getString(2)}" }
        var hash = FNV_OFFSET
        for (byte in "$made|$first".toByteArray()) hash = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME
        return hash
    }

    /** The last row of every table, in [SourceTable] order; `0` for a table that is empty or not there. */
    fun lastRows(): List<Long> = SourceTable.entries.map { table ->
        if (columns.getValue(table)
                .isEmpty()
        ) 0L else one("SELECT MAX(rowid) FROM $prefix${table.suffix}") { it.getLong(1) } ?: 0L
    }

    /** When the first and the last block row were written, in epoch seconds. */
    fun span(): Pair<Long, Long>? {
        val oldest = one("SELECT time FROM ${prefix}block ORDER BY rowid LIMIT 1") { it.getLong(1) } ?: return null
        val newest = one("SELECT time FROM ${prefix}block ORDER BY rowid DESC LIMIT 1") { it.getLong(1) } ?: return null
        return oldest to newest
    }

    /** Reads every lookup table. They are small: a row per world, player, block type. */
    fun tables(): CoreProtectTables = CoreProtectTables(
        worlds = map("SELECT id, world FROM ${prefix}world"),
        users = all("SELECT $userId, user, uuid FROM ${prefix}user") { row ->
            val uuid = row.getString(3)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            row.getInt(1) to CoreProtectUser(row.getString(2) ?: "", uuid)
        }.toMap(),
        materials = map("SELECT id, material FROM ${prefix}material_map"),
        blockData = runCatching { map("SELECT id, data FROM ${prefix}blockdata_map") }.getOrDefault(emptyMap()),
        entities = runCatching { map("SELECT id, entity FROM ${prefix}entity_map") }.getOrDefault(emptyMap()),
    )

    /** The head placed under key [id], or `null` if nothing was kept about it. */
    fun skull(id: Int): SkullRow? = skulls.getOrPut(id) {
        runCatching {
            connection.prepareStatement("SELECT owner, skin FROM ${prefix}skull WHERE $skullId = ?").use { statement ->
                statement.setInt(1, id)
                statement.executeQuery()
                    .use { row -> if (row.next()) SkullRow(row.getString(1), row.getString(2)) else null }
            }
        }.getOrNull()
    }.also { if (skulls.size > SKULLS_REMEMBERED) skulls.clear() }

    /** What `co_entity` kept of the mob killed under key [id], still serialized, or `null` if it kept nothing. */
    fun entity(id: Int): ByteArray? = runCatching {
        connection.prepareStatement("SELECT data FROM ${prefix}entity WHERE $entityId = ?").use { statement ->
            statement.setInt(1, id)
            statement.executeQuery()
                .use { row -> if (row.next()) row.getBytes(1)?.takeIf { it.isNotEmpty() } else null }
        }
    }.getOrNull()

    /** Up to [limit] rows of [table] after [after] and no further than [upTo], oldest first. */
    @Suppress("SqlSourceToSinkFlow")
    fun rows(table: SourceTable, after: Long, upTo: Long, limit: Int): List<SourceRow> {
        val has = columns.getValue(table)
        if (has.isEmpty() || after >= upTo) return emptyList()
        fun column(name: String, missing: String = "NULL") = if (name in has) name else missing
        val own = when (table) {
            SourceTable.BLOCK -> "type, data, ${column("meta")}, ${column("blockdata")}, action, rolled_back"
            SourceTable.SIGN -> "action, ${column("color", "0")}, ${column("color_secondary", "0")}, ${
                column(
                    "data",
                    "0"
                )
            }, " +
                    "${column("waxed", "0")}, " + (1..SIGN_LINES).joinToString(", ") { column("line_$it") }

            SourceTable.CONTAINER -> "type, amount, ${column("metadata")}, action"
            SourceTable.ITEM -> "type, amount, ${column("data")}, action"
            SourceTable.SESSION -> "action"
            SourceTable.COMMAND, SourceTable.CHAT -> "message"
        }
        val sql = "SELECT rowid, time, user, wid, x, y, z, $own FROM $prefix${table.suffix} " +
                "WHERE rowid > ? AND rowid <= ? ORDER BY rowid LIMIT ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, after)
            statement.setLong(2, upTo)
            statement.setInt(3, limit)
            statement.executeQuery().use { rows ->
                val out = ArrayList<SourceRow>(limit)
                while (rows.next()) out += read(table, rows)
                return out
            }
        }
    }

    private fun read(table: SourceTable, row: ResultSet): SourceRow {
        val rowId = row.getLong(1)
        val time = row.getLong(2)
        val user = row.getInt(3)
        val world = row.getInt(4)
        val x = row.getInt(5)
        val y = row.getInt(6)
        val z = row.getInt(7)
        return when (table) {
            SourceTable.BLOCK -> BlockRow(
                rowId, time, user, world, x, y, z,
                type = row.getInt(OWN),
                data = row.getInt(OWN + 1),
                meta = row.getBytes(OWN + 2)?.takeIf { it.isNotEmpty() },
                blockData = row.getBytes(OWN + 3)?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8),
                action = row.getInt(OWN + 4),
                rolledBack = row.getInt(OWN + 5) != 0,
            )

            SourceTable.SIGN -> SignRow(
                rowId, time, user, world, x, y, z,
                action = row.getInt(OWN),
                color = row.getInt(OWN + 1),
                colorSecondary = row.getInt(OWN + 2),
                glowing = row.getInt(OWN + 3),
                waxed = row.getInt(OWN + 4) != 0,
                lines = (0 until SIGN_LINES).map { row.getString(OWN + 5 + it).orEmpty() },
            )

            SourceTable.CONTAINER, SourceTable.ITEM -> ItemRow(
                table, rowId, time, user, world, x, y, z,
                type = row.getInt(OWN),
                amount = row.getInt(OWN + 1),
                metadata = row.getBytes(OWN + 2)?.takeIf { it.isNotEmpty() },
                action = row.getInt(OWN + 3),
            )

            SourceTable.SESSION -> SessionRow(rowId, time, user, world, x, y, z, action = row.getInt(OWN))
            SourceTable.COMMAND, SourceTable.CHAT -> TextRow(
                table,
                rowId,
                time,
                user,
                world,
                x,
                y,
                z,
                row.getString(OWN).orEmpty()
            )
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    private fun columns(table: String): Set<String> = runCatching {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $prefix$table LIMIT 0").use { rows ->
                val meta = rows.metaData
                (1..meta.columnCount).mapTo(HashSet()) { meta.getColumnName(it).lowercase() }
            }
        }
    }.getOrDefault(emptySet())

    private fun map(sql: String): Map<Int, String> =
        all(sql) { row -> row.getString(2)?.let { row.getInt(1) to it } }.filterNotNull().toMap()

    private fun <T> all(sql: String, read: (ResultSet) -> T): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val out = ArrayList<T>()
                while (rows.next()) out += read(rows)
                out
            }
        }

    private fun <T : Any> one(sql: String, read: (ResultSet) -> T?): T? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql)
                .use { rows -> if (rows.next()) read(rows).takeUnless { rows.wasNull() } else null }
        }

    companion object {
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
        private const val BUSY_TIMEOUT_MILLIS = 15_000
        private const val SKULLS_REMEMBERED = 4_096
        private const val SIGN_LINES = 8
        private const val OWN = 8

        fun open(location: CoreProtectLocation): CoreProtectDatabase {
            // The prefix goes into every query as text
            require(location.prefix.all { it.isLetterOrDigit() || it == '_' }) { "table prefix \"${location.prefix}\" is not a name" }
            val connection = when (location) {
                is CoreProtectLocation.File -> {
                    load("org.sqlite.JDBC")
                    // Read-only, so a slip here can never cost somebody their CoreProtect history
                    val url = "jdbc:sqlite:file:${location.path.toAbsolutePath().toUri().rawPath}?mode=ro"
                    DriverManager.getConnection(url).also { opened ->
                        opened.createStatement().use { it.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS") }
                    }
                }

                is CoreProtectLocation.Server -> {
                    load("com.mysql.cj.jdbc.Driver")
                    val properties = Properties().apply {
                        setProperty("user", location.user)
                        setProperty("password", location.password)
                        setProperty("useSSL", "false")
                        setProperty("allowPublicKeyRetrieval", "true")
                        setProperty("characterEncoding", "UTF-8")
                    }
                    val url = "jdbc:mysql://${location.host}:${location.port}/${location.database}"
                    DriverManager.getConnection(url, properties).also { it.isReadOnly = true }
                }
            }
            return try {
                CoreProtectDatabase(connection, location.prefix)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw failure
            }
        }

        private fun load(driver: String) {
            runCatching { Class.forName(driver) }
        }
    }
}
