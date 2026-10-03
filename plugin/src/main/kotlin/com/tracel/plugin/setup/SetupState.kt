package com.tracel.plugin.setup

import java.nio.file.Files
import java.nio.file.Path

/** Whether the welcome setup still waits to be run. It is a marker file, so a restart does not lose it. */
internal class SetupState(dataFolder: Path) {
    /** The setup has been neither finished nor skipped. */
    val pending: Boolean get() = Files.exists(marker)

    private val marker = dataFolder.resolve(MARKER)

    private companion object {
        const val MARKER = "setup.pending"
    }

    /** Marks a fresh install as waiting for its setup. */
    fun begin() {
        runCatching { Files.createFile(marker) }
    }

    /** The setup is done. */
    fun finish() {
        runCatching { Files.deleteIfExists(marker) }
    }
}
