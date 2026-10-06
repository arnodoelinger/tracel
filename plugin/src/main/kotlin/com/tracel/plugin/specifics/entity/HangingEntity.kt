package com.tracel.plugin.specifics.entity

/**
 * Entities that hang on a block and fall off when it goes.
 *
 * @param id the entity type without a namespace
 * @param dropsWhenBlockReturns whether a block put back into the cell it hangs in knocks it off
 */
internal enum class HangingEntity(val id: String, val dropsWhenBlockReturns: Boolean = false) {
    ITEM_FRAME("item_frame"),
    GLOW_ITEM_FRAME("glow_item_frame"),
    PAINTING("painting", dropsWhenBlockReturns = true),
    LEASH_KNOT("leash_knot");

    companion object {
        val ids: Set<String> = entries.mapTo(HashSet()) { it.id }
        val knockedOffIds: Set<String> = entries.filter { it.dropsWhenBlockReturns }.mapTo(HashSet()) { it.id }
    }
}

/** The end of the entity type of a block in flight: sand falling, a block a piston let go of. */
internal const val FALLING_BLOCK_SUFFIX: String = ":falling_block"
