package com.tracel.plugin.dialog

import com.tracel.annotations.CauseKind
import com.tracel.engine.provenance.FateNode
import com.tracel.engine.provenance.ProvenanceNode
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.namesMaterial
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.ActionKind
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.entity.leashHolder
import org.bukkit.Bukkit
import java.util.*

// TODO: rewrite
sealed interface ReportNode {
    data class Line(val text: String) : ReportNode
    data class Tree(val label: String, val children: List<ReportNode>) : ReportNode
    data class Section(val title: String, val children: List<ReportNode>) : ReportNode
}

fun interface ReportSink {
    fun emit(node: ReportNode)
}

object ChatReportSink : ReportSink {
    fun render(node: ReportNode): List<String> = flatten(node, indent = 0, edge = null)

    override fun emit(node: ReportNode) { }

    private fun flatten(node: ReportNode, indent: Int, edge: String?): List<String> {
        val pad = "  ".repeat(indent)
        val mark = edge.orEmpty()
        return when (node) {
            is ReportNode.Line -> listOf("$pad$mark${node.text}")
            is ReportNode.Section ->
                listOf("$pad$mark${node.title}") + node.children.flatMap { flatten(it, indent + 1, null) }
            is ReportNode.Tree ->
                listOf("$pad$mark${node.label}") + node.children.flatMap { flatten(it, indent + 1, edge = "-> ") }
        }
    }
}

fun describeHolder(holder: HolderId): String = when (holder) {
    is HolderId.Block, is HolderId.PlacedBlock -> {
        val x: Int
        val y: Int
        val z: Int
        when (holder) {
            is HolderId.Block -> { x = holder.x; y = holder.y; z = holder.z }
            is HolderId.PlacedBlock -> { x = holder.x; y = holder.y; z = holder.z }
        }
        "block $x,$y,$z"
    }
    is HolderId.Player -> "player ${playerName(holder.uuid)}"
    is HolderId.EnderChest -> "ender chest of ${playerName(holder.uuid)}"
    is HolderId.Entity -> "entity ${holder.uuid}"
    is HolderId.PlacedEntity -> "placed entity ${holder.uuid}"
    is HolderId.ItemEntity -> "item entity ${holder.uuid}"
    is HolderId.Escrow -> "escrow (job ${holder.job.raw})"
    is HolderId.Source -> "source:${holder.kind.name.lowercase()}"
    is HolderId.Sink -> "sink:${holder.kind.name.lowercase()}"
}

private fun playerName(uuid: UUID): String =
    runCatching { Bukkit.getOfflinePlayer(uuid).name }.getOrNull() ?: uuid.toString()

fun structureStepReport(step: StructureStep): ReportNode {
    val at = "${step.at.x},${step.at.y},${step.at.z}"
    val text = when (step) {
        is StructureStep.SetBlock -> {
            val from = step.expected.data.value.substringBefore('[')
            val to = step.target.data.value.substringBefore('[')
            "$at: $from -> $to"
        }
        is StructureStep.SpawnEntity -> "spawn ${step.shape.type.value} at $at"
        is StructureStep.RemoveEntity -> "remove ${step.shape.type.value} at $at"
    }
    return ReportNode.Line(text)
}

fun originReport(node: ProvenanceNode): ReportNode =
    ReportNode.Tree(
        label = "${node.lotId.raw}x lot: ${node.itemKey.material} (txn ${node.createdBy.raw})",
        children = node.via.map(::originReport),
    )

fun fateReport(node: FateNode): ReportNode {
    val holderText = node.currentHolder?.let { " @ ${describeHolder(it)}" }.orEmpty()
    return ReportNode.Tree(
        label = "${node.lotId.raw}x lot: ${node.itemKey.material}$holderText",
        children = node.next.map(::fateReport),
    )
}

fun lookupResultReport(transaction: Transaction, item: String? = null): ReportNode? {
    val flows = if (item == null) transaction.flows
    else transaction.flows.filter { it.itemKey.material.namesMaterial(item) }
    if (flows.isEmpty()) return null
    val who = transaction.causedBy?.let { " by ${describeHolder(it)}" }.orEmpty()
    return ReportNode.Section(
        title = "txn ${transaction.id.raw} @ ${transaction.epochMillis}ms — ${transaction.cause.name.lowercase()}$who",
        children = flows.map { ReportNode.Line(if (transaction.cause == CauseKind.WEAR) it.describeWear() else it.describe()) },
    )
}

private fun Flow.describeWear(): String = "${itemKey.material}: durability changed at ${describeHolder(source)}"

private fun Flow.describe(): String {
    val qty = "${quantity.raw}x ${itemKey.material}"
    return when (kind) {
        FlowKind.MOVE -> "$qty: ${describeHolder(source)} -> ${describeHolder(destination)}"
        FlowKind.MINT -> "$qty: minted -> ${describeHolder(destination)}"
        FlowKind.BURN -> "$qty: ${describeHolder(source)} -> burned"
        FlowKind.TRANSFORM_IN -> "$qty: ${describeHolder(source)} -> crafted"
        FlowKind.TRANSFORM_OUT -> "$qty: crafted -> ${describeHolder(destination)}"
    }
}

fun worldChangeReport(change: WorldChange): ReportNode {
    val who = change.causedBy?.let(::describeHolder) ?: change.cause.name.lowercase()
    val at = "${change.at.x},${change.at.y},${change.at.z}"
    val text = when (val subject = change.subject) {
        is ChangeSubject.Block -> {
            val trampled = trampleOf(subject)
            if (trampled != null) "$who trampled $trampled at $at"
            else "$who ${verbFor(change.action)} ${describeBlock(subject)} at $at"
        }
        is ChangeSubject.Entity ->
            "$who ${leashVerb(subject) ?: verbFor(change.action)} ${subject.type.value} at $at"
    }
    return ReportNode.Line(text)
}

private fun leashVerb(subject: ChangeSubject.Entity): String? {
    val before = subject.before?.extras.leashHolder
    val after = subject.after?.extras.leashHolder
    return when {
        before == null && after != null -> "leashed"
        before != null && after == null -> "unleashed"
        else -> null
    }
}

private fun trampleOf(subject: ChangeSubject.Block): String? {
    val before = subject.before.data.value.substringBefore('[')
    val after = subject.after.data.value.substringBefore('[')
    return when {
        before.endsWith(":farmland") && after.endsWith(":dirt") -> "farmland"
        before.endsWith(":turtle_egg") -> "turtle egg"
        else -> null
    }
}

private fun verbFor(action: ActionKind): String = when (action) {
    ActionKind.BLOCK_PLACE -> "placed"
    ActionKind.BLOCK_BREAK -> "broke"
    ActionKind.BLOCK_CHANGE -> "changed"
    ActionKind.SIGN_EDIT -> "edited"
    ActionKind.ENTITY_SPAWN -> "spawned"
    ActionKind.ENTITY_REMOVE -> "removed"
    ActionKind.ENTITY_CHANGE -> "altered"
}

private fun describeBlock(subject: ChangeSubject.Block): String {
    val before = subject.before.data.value.substringBefore('[')
    val after = subject.after.data.value.substringBefore('[')
    return when {
        subject.after.isAirLike -> before
        subject.before.isAirLike -> after
        else -> "$before -> $after"
    }
}

// Temporary
fun renderStructureStep(step: StructureStep): String =
    ChatReportSink.render(structureStepReport(step)).single()

fun renderLookupResult(transaction: Transaction, item: String? = null): List<String> =
    lookupResultReport(transaction, item)?.let(ChatReportSink::render).orEmpty()

fun renderWorldChange(change: WorldChange): String =
    ChatReportSink.render(worldChangeReport(change)).single()
