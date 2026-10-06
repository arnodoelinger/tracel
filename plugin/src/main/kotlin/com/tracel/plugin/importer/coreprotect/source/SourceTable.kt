package com.tracel.plugin.importer.coreprotect.source

/**
 * The tables an import reads, in the order their rows are taken when two happened in the same second, which is
 * also the order their marks are kept in.
 *
 * A new table goes at the end, or every mark written so far lies.
 */
enum class SourceTable(val suffix: String) {
    BLOCK("block"),
    SIGN("sign"),
    CONTAINER("container"),
    ITEM("item"),
    SESSION("session"),
    COMMAND("command"),
    CHAT("chat"),
}
