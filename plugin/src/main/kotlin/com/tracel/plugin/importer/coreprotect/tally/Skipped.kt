package com.tracel.plugin.importer.coreprotect.tally

/** Why a row was left behind. */
enum class Skipped {
    /** At or after the moment our own history starts: we have it already, and better. */
    OVERLAP,

    /** In a world this server does not have. */
    WORLD,

    /** A block this version of the game does not know. */
    BLOCK,

    /** An entity this version of the game does not know. */
    ENTITY,

    /** An item this version of the game does not know. */
    ITEM,

    /** A row that says nothing happened, or says it in a way this importer cannot read. */
    UNREADABLE,
}
