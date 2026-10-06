package com.tracel.storage.util

/**
 * Checks whether this value refers to [wanted] material, with or without a namespace.
 *
 * Item keys store plain material names, while world log entries may include a namespace.
 */
public fun String.namesMaterial(wanted: String): Boolean =
    equals(wanted, ignoreCase = true) || equals(wanted.substringAfter(':'), ignoreCase = true)
