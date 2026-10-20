package com.tracel.plugin.command.presenter

import com.tracel.api.history.FlowKind
import com.tracel.api.history.ItemFlow
import com.tracel.api.item.ItemHolder
import com.tracel.api.item.SourceKind
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
    class Act(val name: String, val mark: Component, val place: ItemHolder? = null)

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
    fun of(flow: ItemFlow, everything: Boolean = false, byMachine: Boolean = false): Act? {
        val from = flow.from
        val to = flow.to
        if (from == ItemHolder.Rollback || to == ItemHolder.Rollback) return null
        if (listOf(from, to).any { it is ItemHolder.PlacedBlock || it is ItemHolder.PlacedEntity }) return null
        return when (flow.kind) {
            FlowKind.TRANSFORM_IN -> if (everything) Act("used", MINUS) else null
            FlowKind.TRANSFORM_OUT -> Act("crafted", PLUS)
            FlowKind.MINT -> if (isPlayer(to) && from is ItemHolder.Appeared && from.kind == SourceKind.CREATIVE) Act(
                "conjured",
                PLUS
            ) else if (isPlayer(to)) Act("received", PLUS)
            else if (isContainer(to) && from is ItemHolder.Appeared && from.kind == SourceKind.CREATIVE) Act("put", PLUS, to)
            else null

            FlowKind.BURN -> if (isPlayer(from)) Act("lost", MINUS) else if (everything) Act(
                "destroyed",
                MINUS
            ) else null

            FlowKind.MOVE -> when {
                to is ItemHolder.Dropped -> Act("dropped", MINUS)
                from is ItemHolder.Dropped -> Act("picked_up", PLUS, to.takeIf(::isContainer))
                isPlayer(from) && isContainer(to) -> Act("put", PLUS, to)
                isContainer(from) && isPlayer(to) -> Act("took", MINUS, from)
                byMachine && isContainer(from) && isContainer(to) -> Act("transferred", PLUS, to)
                else -> Act("moved", BOTH, to.takeIf(::isContainer))
            }
        }
    }

    private fun isPlayer(holder: ItemHolder) = holder is ItemHolder.Player && !holder.stash

    private fun isContainer(holder: ItemHolder) =
        holder is ItemHolder.Container || holder is ItemHolder.Entity || (holder is ItemHolder.Player && holder.stash)
}
