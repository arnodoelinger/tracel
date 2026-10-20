package com.tracel.api.write

import com.tracel.api.Unstable
import com.tracel.api.access.Grant
import com.tracel.api.world.BlockPlace

/** Why a write did not happen. */
@Unstable
public sealed interface WriteError {
    /** The connection was closed or the plugin disabled. */
    public data object Revoked : WriteError

    /** The admin did not give the plugin [grant]. */
    public data class NotGranted(public val grant: Grant) : WriteError

    /** It cannot be written as asked, and [reason] says why. */
    public data class Unsupported(public val reason: String) : WriteError

    /** The world [place] is in is not loaded. */
    public data class Unloaded(public val place: BlockPlace) : WriteError

    /** The player is not online. */
    public data object Offline : WriteError

    /** It broke, and [message] says how... */
    public data class Failed(public val message: String) : WriteError
}
