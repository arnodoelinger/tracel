package com.tracel.model.item

/**
 * Whether this name refers to [wanted] material, with or without a namespace.
 *
 * Item keys keep plain material names, while a world log entry may carry one.
 */
public fun String.namesMaterial(wanted: String): Boolean =
    equals(wanted, ignoreCase = true) || equals(wanted.substringAfter(':'), ignoreCase = true)
