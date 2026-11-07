package com.tracel.storage.chrono.capsule

enum class CapsuleKind(val version: Int, val noun: String) {
    BLOCKS(4, "capsule"),
    ENTITIES(3, "entity capsule"),
    ITEMS(2, "items capsule"),
}
