package com.tracel.plugin.rollback.structure

/** Which structure pass is running. Only names it in the trace; what the pass does is [StructurePass]. */
enum class StructurePhase(val label: String, val traceName: String) {
    /** Forward create, beside the ledger. */
    RESTORE("restore", "restore blocks"),

    /** Forward destroy nothing waits on. */
    REMOVE("remove", "remove blocks"),

    /** Forward destroy that waits on cargo. */
    CONTESTED("contested", "contested"),

    /** Everything else: undo, and the inverse after a failed ledger. */
    BLOCKS("blocks", "blocks"),
}
