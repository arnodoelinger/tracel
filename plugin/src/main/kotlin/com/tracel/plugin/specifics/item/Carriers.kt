package com.tracel.plugin.specifics.item

import org.bukkit.Material

/** Whether a stack of this holds other items inside it: a bundle of any colour, a crossbow. */
internal fun Material.carriesItems(): Boolean = this == Material.CROSSBOW || name.endsWith("BUNDLE")
