package com.tracel.storage.chrono.capsule

enum class ExtraSide {
    ENTRY,
    EXIT,
    AFTER,
    BREAK,

    ;

    val isCell: Boolean get() = this == ENTRY || this == EXIT
}
