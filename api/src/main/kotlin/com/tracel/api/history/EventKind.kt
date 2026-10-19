package com.tracel.api.history

import com.tracel.api.Unstable

/** Something somebody did that moved neither blocks nor items. */
@Unstable
public enum class EventKind {
    CHAT,
    COMMAND,
    JOIN,
    QUIT,
    DEATH,
    SHOOT,
    HIT,
    CLICK,
    SET,
    PULL,
    PUSH,
    SLEEP,
    TRADE,
    TELEPORT,
}
