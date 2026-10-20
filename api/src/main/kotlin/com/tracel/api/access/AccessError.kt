package com.tracel.api.access

import com.tracel.api.Unstable

/** Why a plugin did not get a capability. */
@Unstable
public sealed interface AccessError {
    /** The admin narrowed this plugin's grants, and [grant] is not among them. */
    public data class NotGranted(public val grant: Grant) : AccessError

    /** The code that asked does not belong to [plugin]: no connecting in someone else's name. */
    public data class NotYourPlugin(public val plugin: String) : AccessError

    /** The plugin is not enabled, so it cannot connect. */
    public data class NotEnabled(public val plugin: String) : AccessError

    /** The admin has not allowed destructive operations (`allow-destructive` in `config.toml`), to anyone. */
    public data object DestructiveNotAllowed : AccessError

    /** The connection was closed, by the plugin, by its disabling, or by `Tracel` stopping. */
    public data object Revoked : AccessError
}
