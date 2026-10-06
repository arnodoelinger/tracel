package com.tracel.plugin.config.migrate

/** What a version of a TOML file changed in the one before it. */
internal sealed interface Change {
    /** Adds `key = literal` to [section], with [comment] above it, unless the section already has the key. */
    class AddKey(
        val section: String,
        val key: String,
        val literal: String,
        val comment: List<String> = emptyList(),
    ) : Change

    /** Renames a key where it stands, keeping its value, unless the new name is already taken. */
    class RenameKey(val section: String, val from: String, val to: String) : Change
}
