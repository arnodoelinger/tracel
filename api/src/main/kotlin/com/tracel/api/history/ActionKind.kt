package com.tracel.api.history

import com.tracel.api.Unstable

/** What a block or entity record did to the thing it names. */
@Unstable
public enum class ActionKind {
    BLOCK_PLACE,
    BLOCK_BREAK,
    BLOCK_CHANGE,
    SIGN_EDIT,
    ENTITY_SPAWN,
    ENTITY_REMOVE,
    ENTITY_CHANGE,
    BLOCK_CLICK,
    BLOCK_GROW,
}
