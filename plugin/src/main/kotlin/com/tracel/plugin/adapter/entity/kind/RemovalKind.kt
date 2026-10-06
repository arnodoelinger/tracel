package com.tracel.plugin.adapter.entity.kind

/** @return whether this removal is a world-log row. */
enum class RemovalKind {
    Ignore,
    SceneryOnly,
    BlamedOnly,
    Record;

    /** @return whether this kind of removal is a world-log row. */
    fun records(scenery: Boolean, blamed: Boolean): Boolean = when (this) {
        Ignore -> false
        SceneryOnly -> scenery
        BlamedOnly -> blamed
        Record -> true
    }
}
