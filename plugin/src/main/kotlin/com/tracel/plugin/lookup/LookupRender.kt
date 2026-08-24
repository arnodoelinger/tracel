package com.tracel.plugin.lookup

import com.tracel.engine.provenance.FateNode
import com.tracel.engine.provenance.ProvenanceNode
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.transaction.Transaction

// TODO: rewrite

/** Holder-kind-agnostic label. */
fun describeHolder(holder: HolderId): String = when (holder) {
    is HolderId.Block -> "block ${holder.x},${holder.y},${holder.z}"
    is HolderId.PlacedBlock -> "placed block ${holder.x},${holder.y},${holder.z}"
    is HolderId.Player -> "player ${holder.uuid}"
    is HolderId.Entity -> "entity ${holder.uuid}"
    is HolderId.ItemEntity -> "item entity ${holder.uuid}"
    is HolderId.Escrow -> "escrow (job ${holder.job.raw})"
    is HolderId.Source -> "source:${holder.kind.name.lowercase()}"
    is HolderId.Sink -> "sink:${holder.kind.name.lowercase()}"
}

/** Renders an [origin][com.tracel.engine.provenance.FlowGraph.originOf] tree as indented lines, one per lot. */
fun renderOrigin(node: ProvenanceNode, indent: Int = 0): List<String> {
    val prefix = "  ".repeat(indent)
    val arrow = if (indent == 0) "" else "<- "
    val line = "$prefix$arrow${node.lotId.raw}x lot: ${node.itemKey.material} (txn ${node.createdBy.raw})"
    return listOf(line) + node.via.flatMap { renderOrigin(it, indent + 1) }
}

/** Renders a [fate][com.tracel.engine.provenance.FlowGraph.fateOf] tree as indented lines, one per lot. */
fun renderFate(node: FateNode, indent: Int = 0): List<String> {
    val prefix = "  ".repeat(indent)
    val arrow = if (indent == 0) "" else "-> "
    val holderText = node.currentHolder?.let { " @ ${describeHolder(it)}" } ?: ""
    val line = "$prefix$arrow${node.lotId.raw}x lot: ${node.itemKey.material}$holderText"
    return listOf(line) + node.next.flatMap { renderFate(it, indent + 1) }
}

/** One `lookup` result line: `<cause> <n>x <material> <source> -> <destination>` per flow in the transaction. */
fun renderLookupResult(transaction: Transaction, item: String? = null): List<String> {
    val flows = if (item == null) transaction.flows else transaction.flows.filter { it.itemKey.material == item }
    if (flows.isEmpty()) return emptyList()
    val header = "txn ${transaction.id.raw} @ ${transaction.epochMillis}ms — ${transaction.cause.name.lowercase()}" +
        (transaction.causedBy?.let { " by ${describeHolder(it)}" } ?: "")
    return listOf(header) + flows.map { "  ${it.describe()}" }
}

/** Flow describer. */
private fun Flow.describe(): String = when (kind) {
    FlowKind.MOVE -> "${quantity.raw}x ${itemKey.material}: ${describeHolder(source)} -> ${describeHolder(destination)}"
    FlowKind.MINT -> "${quantity.raw}x ${itemKey.material}: minted -> ${describeHolder(destination)}"
    FlowKind.BURN -> "${quantity.raw}x ${itemKey.material}: ${describeHolder(source)} -> burned"
    FlowKind.TRANSFORM_IN -> "${quantity.raw}x ${itemKey.material}: ${describeHolder(source)} -> crafted"
    FlowKind.TRANSFORM_OUT -> "${quantity.raw}x ${itemKey.material}: crafted -> ${describeHolder(destination)}"
}

/** Sum of every matched flow's quantity — the `#count` aggregate. */
fun countUnits(transactions: List<Transaction>, item: String? = null): Long =
    transactions.sumOf { txn -> txn.flows.filter { item == null || it.itemKey.material == item }.sumOf { it.quantity.raw } }
