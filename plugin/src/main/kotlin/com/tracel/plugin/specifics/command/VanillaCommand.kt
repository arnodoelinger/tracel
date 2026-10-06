package com.tracel.plugin.specifics.command

/** The namespace vanilla commands and ids can be spelled with. */
internal const val VANILLA_NAMESPACE: String = "minecraft:"

/** Whether this command line runs the vanilla command [name], with or without the namespace. */
internal fun String.isCommand(name: String): Boolean {
    val bare = removePrefix(VANILLA_NAMESPACE)
    return bare.regionMatches(
        0,
        name,
        0,
        name.length,
        ignoreCase = true
    ) && (bare.length == name.length || bare[name.length] == ' ')
}
