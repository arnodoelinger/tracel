package com.tracel.plugin.command.presenter

import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

/** What a flow of items reads as in lookup: put, took, dropped, picked up... */
internal object ItemPresenter {
    val PLUS = tr("common.mark.plus")
    val MINUS = tr("common.mark.minus")
    val BOTH = tr("common.mark.both")

    private val CONTAINER = Family("container", "put", "took", true)
    private val GROUND = Family("ground", "picked_up", "dropped", false)
    private val SUPPLY = Family("supply", "received", "lost", false)

    /** [name] is the verb under `lookup.verb`; [place] is the container the items went to or came from. */
    class Act(val name: String, val mark: Component, val place: HolderId? = null)

    /**
     * Acts that undo each other: items put in and taken out, dropped and picked up, lost and received. A run of
     * them within a minute is one line that says where the items ended up, not a count of every click.
     */
    class Family(val name: String, val plus: String, val minus: String, val container: Boolean)

    /** Determines the family associated with a given action. */
    fun family(act: String): Family? = when (act) {
        "put", "took" -> CONTAINER
        "picked_up", "dropped" -> GROUND
        "received", "lost" -> SUPPLY
        else -> null
    }

    /** Creates an [Act] instance based on the provided flow information and conditions. */
    fun of(flow: Flow, everything: Boolean = false): Act? {
        val from = flow.source
        val to = flow.destination
        if (from is HolderId.Escrow || to is HolderId.Escrow) return null
        if (listOf(from, to).any { it is HolderId.PlacedBlock || it is HolderId.PlacedEntity }) {
            return if (everything) Act("moved", BOTH) else null
        }
        return when (flow.kind) {
            FlowKind.TRANSFORM_IN -> if (everything) Act("used", MINUS) else null
            FlowKind.TRANSFORM_OUT -> Act("crafted", PLUS)
            FlowKind.MINT -> if (to is HolderId.Player && from is HolderId.Source && from.kind == SourceKind.CREATIVE) Act(
                "conjured",
                PLUS
            ) else if (to is HolderId.Player) Act("received", PLUS) else if (everything) Act(
                "created",
                PLUS
            ) else null

            FlowKind.BURN -> if (from is HolderId.Player) Act("lost", MINUS) else if (everything) Act(
                "destroyed",
                MINUS
            ) else null

            FlowKind.MOVE -> when {
                to is HolderId.ItemEntity -> Act("dropped", MINUS)
                from is HolderId.ItemEntity -> Act("picked_up", PLUS, to.takeIf(::isContainer))
                from is HolderId.Player && isContainer(to) -> Act("put", PLUS, to)
                isContainer(from) && to is HolderId.Player -> Act("took", MINUS, from)
                else -> Act("moved", BOTH, to.takeIf(::isContainer))
            }
        }
    }

    private fun isContainer(holder: HolderId) =
        holder is HolderId.Block || holder is HolderId.Entity || holder is HolderId.PlayerStash
}
