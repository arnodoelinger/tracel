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
                Change.AddKey(
                    "api", "excuse-slowpokes", "[]",
                    listOf(
                        "# Addons that keep running however slow they are, by addon ID",
                        "# Normally an addon that keeps Tracel waiting over a second, or hangs for five, is",
                        "# thrown out until the next restart; one listed here is only named in the log",
                        "# For when the addon is slow and its author is slower",
                        "# e.g., excuse-slowpokes = [\"backpacks\"]",
                    ),
                ),
                Change.AddKey(
                    "api", "allow-destructive", "false",
                    listOf(
                        "# Lets plugins purge or replace your history through the API. There is no undoing it",
                    ),
                ),
            ),
        ),
    )

    const val NOTE = "# Config version. Don't change this"
    const val PRESETS_NOTE = "# Presets version. Don't change this"
}
