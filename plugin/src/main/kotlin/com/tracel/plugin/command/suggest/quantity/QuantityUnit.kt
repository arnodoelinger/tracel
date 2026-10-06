package com.tracel.plugin.command.suggest.quantity

import net.kyori.adventure.text.Component

internal data class QuantityUnit(
    val suffix: String,
    val describe: (Long) -> Component,
)
