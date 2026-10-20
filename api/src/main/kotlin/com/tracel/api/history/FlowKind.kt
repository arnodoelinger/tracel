package com.tracel.api.history

import com.tracel.api.Unstable

/** What an [ItemFlow] is. */
@Unstable
public enum class FlowKind {
    /** Items relocated from one holder to another; nothing created or destroyed. */
    MOVE,

    /** Items created: they come from an appeared-from-nowhere holder. */
    MINT,

    /** Items destroyed: they go to a gone-for-good holder. */
    BURN,

    /** Items consumed as a crafting ingredient. */
    TRANSFORM_IN,

    /** Items produced by crafting. */
    TRANSFORM_OUT,
}
