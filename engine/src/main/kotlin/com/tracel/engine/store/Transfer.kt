package com.tracel.engine.store

import java.nio.file.Path

/** What an export wrote, or an import read. */
public data class ExportSummary(
    public val rows: Long,
    public val bytes: Long,
    public val file: Path,
    public val oldest: Long? = null,
    public val newest: Long? = null,
)

/** Thrown when the caller's stop flag went up while an export or import was still checking its file. */
public class StoppedByRequest : RuntimeException("stopped on request")

/** An import was cut short and cannot be finished, so what is in the database is a part of the file. */
public class ImportInterrupted(message: String) : IllegalStateException(message)

/** The database was written by a version this one cannot read. */
public class StoreFormatException(message: String) : IllegalStateException(message)
