package com.tracel.plugin.command.presenter

import com.tracel.api.actor.Actor
import com.tracel.api.history.ActionKind
import com.tracel.api.history.BlockRecord
import com.tracel.api.history.EntityRecord
import com.tracel.api.history.FlowKind
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.item.ItemHolder
import com.tracel.plugin.command.presenter.line.TransitionPresenter
import com.tracel.plugin.specifics.block.AirBlock
import java.util.*

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
         * Counts [records] for [player]. They are asked for the player already; this is the tally, and it skips what the
         * player only stood next to.
         */
        fun of(player: UUID, records: List<HistoryRecord>): PlayerReport {
            val me = Actor.Player(player)
            val meHolder = ItemHolder.Player(player)
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

            for (record in records) {
                val (action, at) = when (record) {
                    is BlockRecord -> record.action to record.place
                    is EntityRecord -> record.action to record.place
                    else -> continue
                }
                if (record.actor != me) continue
                seen(record.at.toEpochMilli())
                column(at.x, at.y, at.z)
                val block = record as? BlockRecord
                when (action) {
                    ActionKind.BLOCK_PLACE -> {
                        place++
                        block?.let { placed.merge(it.after.named(), 1, Int::plus) }
                    }

                    ActionKind.BLOCK_BREAK -> {
                        breakage++
                        block?.let { broken.merge(it.before.named(), 1, Int::plus) }
                    }

                    ActionKind.BLOCK_CHANGE, ActionKind.BLOCK_GROW -> {
                        if (action == ActionKind.BLOCK_CHANGE && block != null && !block.after.isAir() &&
                            TransitionPresenter.of(block.before, block.after, false) == null &&
                            (block.before != block.after || block.beforeText != block.afterText)
                        ) {
                            place++
                            placed.merge(block.after.named(), 1, Int::plus)
                        } else {
                            change++
                        }
                    }
                    ActionKind.SIGN_EDIT -> signs++
                    ActionKind.ENTITY_SPAWN -> spawned++
                    ActionKind.ENTITY_REMOVE -> {
                        val type = (record as? EntityRecord)?.type
                        if (type != null) kills.merge(type.removePrefix("minecraft:"), 1, Int::plus)
                    }

                    ActionKind.ENTITY_CHANGE, ActionKind.BLOCK_CLICK -> Unit
                }
            }

            var mine = 0
            for (flows in records.filterIsInstance<ItemFlow>().groupBy { it.cursor }.values) {
                var touched = false
                for (flow in flows) {
                    if (flow.kind != FlowKind.MOVE) continue
                    val n = flow.amount
                    val name = flow.item.material.lowercase()
                    val from = flow.from
                    val to = flow.to
                    when {
                        to == meHolder && from.isStorage() -> {
                            took += n; items.merge(name, n, Long::plus); touched = true
                        }

                        from == meHolder && to.isStorage() -> {
                            stored += n; items.merge(name, n, Long::plus); touched = true
                        }

                        from == meHolder && to is ItemHolder.Dropped -> {
                            dropped += n; touched = true
                        }

                        to == meHolder && from is ItemHolder.Dropped -> {
                            pickedUp += n; touched = true
                        }
                    }
                }
                if (touched) {
                    mine++
                    seen(flows.first().at.toEpochMilli())
                    flows.first().place?.let { column(it.x, it.y, it.z) }
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

        private fun String.named(): String = removePrefix("minecraft:").substringBefore('[')

        private fun String.isAir(): Boolean = AirBlock.entries.any { it.id == this }

        private fun ItemHolder.isStorage(): Boolean =
            this is ItemHolder.Container || this is ItemHolder.PlacedBlock || this is ItemHolder.Entity ||
                    this is ItemHolder.PlacedEntity
    }
}
