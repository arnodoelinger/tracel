package com.tracel.model.world

/** What a world change did to the thing it names. */
public enum class ActionKind {
    BLOCK_PLACE,
    BLOCK_BREAK,
    BLOCK_CHANGE,
    SIGN_EDIT,
    ENTITY_SPAWN,
    ENTITY_REMOVE,
    ENTITY_CHANGE,
}
