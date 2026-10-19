package com.tracel.plugin.config.migrate

/** What the config files changed from one version to the next. */
internal object FileVersions {
    val CONFIG_STEPS: List<FileStep> = listOf(
        FileStep(
            to = 2,
            changes = listOf(
                Change.AddKey(
                    "api", "grants", "{}",
                    listOf(
                        "# What other plugins may do through the Tracel API, by plugin name",
                        "# By default, every plugin may do everything, but a plugin listed here may do",
                        "# only what its list names",
                        "# rollback = roll back, preview and undo",
                        "# attribute = change blocks, give items and record events",
                        "# quiet = change the world without logging it",
                        "# e.g., grants = { Arena = [\"attribute\"], Shop = [] }",
                    ),
                    before = "advanced",
                ),
            ),
        ),
    )

    const val NOTE = "# Config version. Don't change this"
    const val PRESETS_NOTE = "# Presets version. Don't change this"
}
