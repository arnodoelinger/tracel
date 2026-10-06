package com.tracel.plugin.importer.coreprotect.source

import java.nio.file.Path

/** Where a `CoreProtect` database is kept. */
sealed interface CoreProtectLocation {
    /** What its tables are prefixed with, `co_` unless somebody changed it. */
    val prefix: String

    /** The `SQLite` file `CoreProtect` writes by default. */
    data class File(val path: Path, override val prefix: String = DEFAULT_PREFIX) : CoreProtectLocation {
        override fun toString(): String = path.fileName.toString()
    }

    /** A `MySQL` server. [toString] leaves the password out: it ends up in chat. */
    data class Server(
        val host: String,
        val port: Int,
        val database: String,
        val user: String,
        val password: String,
        override val prefix: String = DEFAULT_PREFIX,
    ) : CoreProtectLocation {
        override fun toString(): String = "mysql://$host:$port/$database"
    }

    companion object {
        const val DEFAULT_PREFIX = "co_"
    }
}
