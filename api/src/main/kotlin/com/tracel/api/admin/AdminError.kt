package com.tracel.api.admin

import com.tracel.api.Unstable
import java.nio.file.Path

/** Why an admin call did nothing, or did not finish. */
@Unstable
public sealed interface AdminError {
    /** A purge, import or migration is running already, or a rollback holds what it needs. */
    public data object Busy : AdminError

    /** The connection was closed or the plugin disabled. */
    public data object Revoked : AdminError

    /** The admin asked to stop, and it did. */
    public data object Stopped : AdminError

    /** The file system said no to [folder]. */
    public data class Denied(public val folder: Path) : AdminError

    /** The disk is full. */
    public data object NoSpace : AdminError

    /** There is no snapshot of that name, and [name] is what was asked for. */
    public data class NoSuchFile(public val name: String) : AdminError

    /** [name] is not the name of a snapshot in the export directory. */
    public data class BadName(public val name: String) : AdminError

    /** There is no `CoreProtect` database to move over. */
    public data object NoSource : AdminError

    /**
     * The database numbers its own history from the very start,
     * so history from elsewhere has no room.
     */
    public data object NoRoom : AdminError

    /** It broke, and [message] says how. */
    public data class Failed(public val message: String) : AdminError
}
