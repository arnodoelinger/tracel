package com.tracel.plugin.command.presenter

import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.ActionKind
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockShape
import java.util.*

/** Where a player was busiest: the center of the 16 x 16 column with the most records. */
internal data class Hotspot(val x: Int, val y: Int, val z: Int, val records: Int)

/** What one player did in a window, counted. */
internal data class PlayerReport(
    val records: Int,
    val placed: Int,
    val broken: Int,
    val changed: Int,
    val signs: Int,
    val topPlaced: List<Pair<String, Int>>,
    val topBroken: List<Pair<String, Int>>,
    val took: Long,
    val stored: Long,
    val dropped: Long,
    val pickedUp: Long,
    val topItems: List<Pair<String, Long>>,
    val kills: List<Pair<String, Int>>,
    val spawned: Int,
    val firstAt: Long?,
    val lastAt: Long?,
    val hotspot: Hotspot?,
) {
    companion object {
        private const val TOP = 3

        /**
         * Counts [changes] and [txns] for [player]. Both logs are asked for the player already; this is the
         * tally, and it skips what the player only stood next to.
         */
        fun of(player: UUID, changes: List<WorldChange>, txns: List<Transaction>): PlayerReport {
            val me = HolderId.Player(player)
            val placed = HashMap<String, Int>()
            val broken = HashMap<String, Int>()
            val kills = HashMap<String, Int>()
            val items = HashMap<String, Long>()
            val columns = HashMap<Long, IntArray>()
            var place = 0
            var breakage = 0
            var change = 0
            var signs = 0
            var spawned = 0
            var first: Long? = null
            var last: Long? = null
            var took = 0L
            var stored = 0L
            var dropped = 0L
            var pickedUp = 0L

            fun seen(at: Long) {
                first = first?.let { minOf(it, at) } ?: at
                last = last?.let { maxOf(it, at) } ?: at
            }

            fun column(x: Int, y: Int, z: Int) {
                val key = ((x shr 4).toLong() shl 32) or ((z shr 4).toLong() and 0xFFFFFFFFL)
                val slot = columns.getOrPut(key) { intArrayOf(0, 0, 0, 0) }
                slot[0]++
                slot[1] += y
                slot[2] = x
                slot[3] = z
            }

            for ((_, action, _, causedBy, epochMillis, at, subject) in changes) {
                if (causedBy != me) continue
                seen(epochMillis)
                column(at.x, at.y, at.z)
                when (action) {
                    ActionKind.BLOCK_PLACE -> {
                        place++
                        (subject as? ChangeSubject.Block)?.after?.let { placed.merge(it.name(), 1, Int::plus) }
                    }

                    ActionKind.BLOCK_BREAK -> {
                        breakage++
                        (subject as? ChangeSubject.Block)?.before?.let { broken.merge(it.name(), 1, Int::plus) }
                    }

                    ActionKind.BLOCK_CHANGE, ActionKind.BLOCK_GROW -> change++
                    ActionKind.SIGN_EDIT -> signs++
                    ActionKind.ENTITY_SPAWN -> spawned++
                    ActionKind.ENTITY_REMOVE -> {
                        val type = (subject as? ChangeSubject.Entity)?.type?.value
                        if (type != null) kills.merge(type.removePrefix("minecraft:"), 1, Int::plus)
                    }

                    ActionKind.ENTITY_CHANGE, ActionKind.BLOCK_CLICK -> Unit
                }
            }

            var mine = 0
            for ((_, _, epochMillis, _, _, flows, _, at) in txns) {
                var touched = false
                for ((itemKey, quantity, from, to, kind) in flows) {
                    if (kind != FlowKind.MOVE) continue
                    val n = quantity.raw
                    val name = itemKey.material.lowercase()
                    when {
                        to == me && from.isStorage() -> {
                            took += n; items.merge(name, n, Long::plus); touched = true
                        }

                        from == me && to.isStorage() -> {
                            stored += n; items.merge(name, n, Long::plus); touched = true
                        }

                        from == me && to is HolderId.ItemEntity -> {
                            dropped += n; touched = true
                        }

                        to == me && from is HolderId.ItemEntity -> {
                            pickedUp += n; touched = true
                        }
                    }
                }
                if (touched) {
                    mine++
                    seen(epochMillis)
                    at?.let { column(it.x, it.y, it.z) }
                }
            }

            val busiest = columns.values.maxByOrNull { it[0] }
            return PlayerReport(
                records = place + breakage + change + signs + spawned + kills.values.sum() + mine,
                placed = place,
                broken = breakage,
                changed = change,
                signs = signs,
                topPlaced = placed.top(),
                topBroken = broken.top(),
                took = took,
                stored = stored,
                dropped = dropped,
                pickedUp = pickedUp,
                topItems = items.entries.sortedByDescending { it.value }.take(TOP).map { it.key to it.value },
                kills = kills.top(),
                spawned = spawned,
                firstAt = first,
                lastAt = last,
                hotspot = busiest?.let { Hotspot(it[2], it[1] / it[0], it[3], it[0]) },
            )
        }

        private fun <T : Comparable<T>> Map<String, T>.top(): List<Pair<String, T>> =
            entries.sortedByDescending { it.value }.take(TOP).map { it.key to it.value }

        private fun BlockShape.name(): String =
            data.value.removePrefix("minecraft:").substringBefore('[')

        private fun HolderId.isStorage(): Boolean =
            this is HolderId.Block || this is HolderId.PlacedBlock || this is HolderId.Entity ||
                    this is HolderId.PlacedEntity
    }
}
