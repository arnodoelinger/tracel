package com.tracel.plugin.config.migrate

/** The changes that take a file to version [to] from the one before. */
internal class FileStep(val to: Int, val changes: List<Change>)

/** [text] after migrating: [from] is [to] when nothing was done to it. */
internal class Migrated(val text: String, val from: Int, val to: Int) {
    val changed: Boolean get() = from != to
}
