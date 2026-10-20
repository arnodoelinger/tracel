package com.tracel.api.history

import com.tracel.api.Unstable

/** Why a read gave nothing back. */
@Unstable
public sealed interface HistoryReadError {
    /** History holds no such thing. */
    public data object NotFound : HistoryReadError

    /** The query asks something history cannot answer yet, and [reason] says what. */
    public data class Unsupported(public val reason: String) : HistoryReadError

    /**
     * The read itself broke, and [message] says how.
     *
     * Asking again may work.
     */
    public data class Failed(public val message: String) : HistoryReadError
}
