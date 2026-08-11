package com.tracel.model.flow

/** What a [Flow] represents. */
public enum class FlowKind {
    /** Relocated from one real holder to another — nothing created or destroyed. */
    MOVE,

    /** Created: flows from a `HolderId.Source`. */
    MINT,

    /** Destroyed: flows to a `HolderId.Sink`. */
    BURN,

    /** Consumed as a crafting ingredient. */
    TRANSFORM_IN,

    /** Produced by crafting. */
    TRANSFORM_OUT,
}
