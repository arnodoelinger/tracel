package com.tracel.plugin.config.migrate

/** What the config files changed from one version to the next. */
internal object FileVersions {
    val CONFIG_STEPS: List<FileStep> = emptyList()

    const val NOTE = "# Config version. Don't change this"
    const val PRESETS_NOTE = "# Presets version. Don't change this"
}
