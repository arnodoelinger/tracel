package com.tracel.plugin.specifics.inventory

/** Where things go in a furnace, a smoker, a blast furnace. */
internal enum class FurnaceSlot(val slot: Int) {
    INPUT(0),
    FUEL(1),
    RESULT(2),
}
