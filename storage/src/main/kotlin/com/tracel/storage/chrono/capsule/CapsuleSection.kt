package com.tracel.storage.chrono.capsule

enum class CapsuleSection {
    SUBJECTS,
    ACTORS,
    KINDS,
    CELLS,
    ROWS,
    TAIL,

    ;

    companion object {
        val COUNT: Int = entries.size
    }
}
