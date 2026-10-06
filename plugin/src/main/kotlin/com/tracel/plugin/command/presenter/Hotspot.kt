package com.tracel.plugin.command.presenter

/** Where a player was busiest: the center of the 16 x 16 column with the most records. */
internal data class Hotspot(val x: Int, val y: Int, val z: Int, val records: Int)
