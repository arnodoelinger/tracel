package com.tracel.plugin.importer.coreprotect.translate

/** What a block entity carried that its block state does not say. */
sealed interface BlockDetail {
    /** Both sides of a sign: eight lines, front first, the way a client would show them with `§` codes. */
    class Sign(
        val lines: List<String>,
        val color: Int,
        val colorBack: Int,
        val glowing: Boolean,
        val glowingBack: Boolean,
        val waxed: Boolean,
    ) : BlockDetail

    class Command(val command: String) : BlockDetail

    class Banner(val patterns: List<Map<*, *>>) : BlockDetail

    class Head(val owner: String?, val skin: String?) : BlockDetail

    class Spawner(val entity: String) : BlockDetail
}
