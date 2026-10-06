package com.tracel.plugin.importer.coreprotect.source

import java.util.*

/** One row of `co_user`. A name that starts with `#` is not a player. */
class CoreProtectUser(val name: String, val uuid: UUID?)

/** The lookup tables every `co_block` row points into. */
class CoreProtectTables(
    val worlds: Map<Int, String>,
    val users: Map<Int, CoreProtectUser>,
    val materials: Map<Int, String>,
    val blockData: Map<Int, String>,
    val entities: Map<Int, String>,
)
