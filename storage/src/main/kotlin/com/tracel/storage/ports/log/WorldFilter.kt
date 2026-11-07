package com.tracel.storage.ports.log

/** How a lookup's material is matched against a stored block state. */
internal object WorldFilter {
    /** Whether [blockData] is [material] or one of its [aliases]: a bare name matches every state of it. */
    fun matches(blockData: String, material: String, aliases: Set<String>): Boolean = blockData.matchesAny(material, aliases)

    private fun String.matchesAny(material: String, aliases: Set<String>): Boolean =
        materialEquals(material) || aliases.any { materialEquals(it) }

    private fun String.materialEquals(material: String): Boolean {
        if ('[' in material) return equals(material, ignoreCase = true)
        val stored = substringBefore('[')
        if (stored.equals(material, ignoreCase = true)) return true
        val bare = stored.substringAfter(':')
        val wanted = material.substringAfter(':')
        return bare.equals(wanted, ignoreCase = true)
    }
}
