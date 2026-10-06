package com.tracel.plugin.importer.coreprotect.source

/** One row of any table: who, when and where. */
sealed class SourceRow(
    val rowId: Long,
    val time: Long,
    val user: Int,
    val world: Int,
    val x: Int,
    val y: Int,
    val z: Int
) {
    abstract val table: SourceTable
}

/** One row of `co_block`: a block placed or broken, a click, or a kill. */
class BlockRow(
    rowId: Long, time: Long, user: Int, world: Int, x: Int, y: Int, z: Int,
    val type: Int,
    val data: Int,
    val meta: ByteArray?,
    val blockData: String?,
    val action: Int,
    val rolledBack: Boolean,
) : SourceRow(rowId, time, user, world, x, y, z) {
    override val table get() = SourceTable.BLOCK
}

/** One row of `co_sign`: everything a sign said at one moment, both sides. */
class SignRow(
    rowId: Long, time: Long, user: Int, world: Int, x: Int, y: Int, z: Int,
    val action: Int,
    val color: Int,
    val colorSecondary: Int,
    val glowing: Int,
    val waxed: Boolean,
    val lines: List<String>,
) : SourceRow(rowId, time, user, world, x, y, z) {
    override val table get() = SourceTable.SIGN
}

/** One row of `co_container` or `co_item`: so many of one item, put, taken, dropped, picked up. */
class ItemRow(
    override val table: SourceTable,
    rowId: Long, time: Long, user: Int, world: Int, x: Int, y: Int, z: Int,
    val type: Int,
    val amount: Int,
    val metadata: ByteArray?,
    val action: Int,
) : SourceRow(rowId, time, user, world, x, y, z)

/** One row of `co_chat` or `co_command`. */
class TextRow(
    override val table: SourceTable,
    rowId: Long, time: Long, user: Int, world: Int, x: Int, y: Int, z: Int,
    val message: String,
) : SourceRow(rowId, time, user, world, x, y, z)

/** One row of `co_session`: somebody joining or leaving. */
class SessionRow(
    rowId: Long, time: Long, user: Int, world: Int, x: Int, y: Int, z: Int,
    val action: Int,
) : SourceRow(rowId, time, user, world, x, y, z) {
    override val table get() = SourceTable.SESSION
}

/** One row of `co_skull`: whose head a placed head is. */
class SkullRow(val owner: String?, val skin: String?)
