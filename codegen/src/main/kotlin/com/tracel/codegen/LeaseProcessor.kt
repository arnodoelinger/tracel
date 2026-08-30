package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

private const val LEASE = "com.tracel.annotations.Lease"
private const val LEASE_STORE = "com.tracel.annotations.LeaseStore"

/** Emits lease token, registry glue, and the in-memory store that checks in for you. */
public class LeaseProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()
        written = true
        resolver.getSymbolsWithAnnotation(LEASE)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { generateProtocol(it) }
        resolver.getSymbolsWithAnnotation(LEASE_STORE)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { generateStore(it) }
        return emptyList()
    }

    private fun generateProtocol(marker: KSClassDeclaration) {
        val pkg = marker.packageName.asString()
        val file = marker.containingFile
        val deps = if (file != null) Dependencies(false, file) else Dependencies(false)
        write(deps, pkg, "LotLease") {
            appendLine("package $pkg")
            appendLine()
            appendLine("import com.tracel.model.id.LotId")
            appendLine("import com.tracel.model.id.RollbackJobId")
            appendLine()
            appendLine("/** Proof that [job] holds exclusive rights over [lotIds]. */")
            appendLine("public class LotLease private constructor(")
            appendLine("    public val job: RollbackJobId,")
            appendLine("    public val lotIds: Set<LotId>,")
            appendLine(") {")
            appendLine("    internal companion object {")
            appendLine("        internal fun mint(job: RollbackJobId, lotIds: Set<LotId>): LotLease = LotLease(job, lotIds)")
            appendLine("    }")
            appendLine("}")
        }
        write(deps, pkg, "LeaseAcquisition") {
            appendLine("package $pkg")
            appendLine()
            appendLine("import com.tracel.model.id.LotId")
            appendLine("import com.tracel.model.id.RollbackJobId")
            appendLine()
            appendLine("/** Result of [LotLeaseRegistry.acquire]. */")
            appendLine("public sealed interface LeaseAcquisition {")
            appendLine("    public data class Granted(public val lease: LotLease) : LeaseAcquisition")
            appendLine("    public data class Denied(public val conflicts: Map<LotId, RollbackJobId>) : LeaseAcquisition")
            appendLine("}")
        }
        write(deps, pkg, "LotLeaseRegistry") {
            appendLine("package $pkg")
            appendLine()
            appendLine("import com.tracel.engine.journal.SimulatedCrash")
            appendLine("import com.tracel.model.id.LotId")
            appendLine("import com.tracel.model.id.RollbackJobId")
            appendLine("import kotlin.coroutines.cancellation.CancellationException")
            appendLine()
            appendLine("/** Exclusive lot reservation. Acquire/extend/holdingFor are generated; storage is not. */")
            appendLine("public abstract class LotLeaseRegistry {")
            appendLine("    public suspend fun acquire(job: RollbackJobId, lotIds: Set<LotId>): LeaseAcquisition {")
            appendLine("        val conflicts = tryReserve(job, lotIds)")
            appendLine("        return if (conflicts.isEmpty()) {")
            appendLine("            LeaseAcquisition.Granted(LotLease.mint(job, lotIds))")
            appendLine("        } else {")
            appendLine("            LeaseAcquisition.Denied(conflicts)")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    public suspend fun extend(lease: LotLease, additionalLotIds: Set<LotId>): LeaseAcquisition =")
            appendLine("        acquire(lease.job, lease.lotIds + additionalLotIds)")
            appendLine()
            appendLine("    public suspend inline fun <T> holdingFor(job: RollbackJobId, block: () -> T): T {")
            appendLine("        val result = try {")
            appendLine("            block()")
            appendLine("        } catch (crash: SimulatedCrash) {")
            appendLine("            throw crash")
            appendLine("        } catch (cancelled: CancellationException) {")
            appendLine("            throw cancelled")
            appendLine("        } catch (failure: Throwable) {")
            appendLine("            release(job)")
            appendLine("            throw failure")
            appendLine("        }")
            appendLine("        release(job)")
            appendLine("        return result")
            appendLine("    }")
            appendLine()
            appendLine("    public abstract suspend fun release(job: RollbackJobId)")
            appendLine("    public abstract suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId>")
            appendLine("    public abstract suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId>")
            appendLine("    protected abstract suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId>")
            appendLine("}")
        }
    }

    private fun generateStore(klass: KSClassDeclaration) {
        val pkg = klass.packageName.asString()
        val name = klass.simpleName.asString()
        val file = klass.containingFile
        val deps = if (file != null) Dependencies(false, file) else Dependencies(false)
        write(deps, pkg, "${name}Store") {
            appendLine("package $pkg")
            appendLine()
            appendLine("import com.tracel.model.id.LotId")
            appendLine("import com.tracel.model.id.RollbackJobId")
            appendLine()
            appendLine("/** In-memory exclusive map. Every mutator already called [SingleWriterGuard.checkIn]. */")
            appendLine("public abstract class ${name}Store : LotLeaseRegistry() {")
            appendLine("    private val writer = SingleWriterGuard()")
            appendLine("    private data class Entry(val job: RollbackJobId, val acquiredAtMillis: Long)")
            appendLine("    private val holders = mutableMapOf<LotId, Entry>()")
            appendLine()
            appendLine("    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> {")
            appendLine("        writer.checkIn()")
            appendLine("        val conflicts = lotIds.mapNotNull { lotId ->")
            appendLine("            holders[lotId]?.takeIf { it.job != job }?.let { lotId to it.job }")
            appendLine("        }.toMap()")
            appendLine("        if (conflicts.isNotEmpty()) return conflicts")
            appendLine("        val now = System.currentTimeMillis()")
            appendLine("        for (lotId in lotIds) holders[lotId] = Entry(job, now)")
            appendLine("        return emptyMap()")
            appendLine("    }")
            appendLine()
            appendLine("    override suspend fun release(job: RollbackJobId) {")
            appendLine("        writer.checkIn()")
            appendLine("        holders.entries.removeAll { it.value.job == job }")
            appendLine("    }")
            appendLine()
            appendLine("    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> {")
            appendLine("        writer.checkIn()")
            appendLine("        val now = System.currentTimeMillis()")
            appendLine("        val toTransfer = holders.filterValues { it.job == from }.keys.toSet()")
            appendLine("        for (lotId in toTransfer) holders[lotId] = Entry(to, now)")
            appendLine("        return toTransfer")
            appendLine("    }")
            appendLine()
            appendLine("    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> {")
            appendLine("        writer.checkIn()")
            appendLine("        val abandoned = holders.values")
            appendLine("            .filter { nowMillis - it.acquiredAtMillis > maxAgeMillis }")
            appendLine("            .mapTo(mutableSetOf()) { it.job }")
            appendLine("        holders.entries.removeAll { it.value.job in abandoned }")
            appendLine("        return abandoned")
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun write(deps: Dependencies, pkg: String, name: String, body: StringBuilder.() -> Unit) {
        val stream = codeGenerator.createNewFile(deps, pkg, name)
        stream.bufferedWriter().use { it.write(buildString(body)) }
    }
}
