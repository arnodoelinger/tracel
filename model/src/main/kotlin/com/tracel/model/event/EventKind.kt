package com.tracel.model.event

/** What somebody did that changed neither the world nor who holds what. */
public enum class EventKind {
    CHAT,
    COMMAND,
    JOIN,
    QUIT,
    DEATH,
    SHOOT,
    HIT,
}
