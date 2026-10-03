package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo

private const val LEASE = "com.tracel.annotations.Lease"
private const val LEASE_STORE = "com.tracel.annotations.LeaseStore"

private val LOT_ID = ClassName("com.tracel.model.id", "LotId")
private val JOB_ID = ClassName("com.tracel.model.id", "RollbackJobId")

/** Emits the lease schema next to the `@Lease` marker, and the in-memory store under it. */
public class LeaseProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()
        written = true

        val markers = resolver.getSymbolsWithAnnotation(LEASE).filterIsInstance<KSClassDeclaration>().toList()
        markers.forEach(::generateSchema)

        resolver.getSymbolsWithAnnotation(LEASE_STORE)
            .filterIsInstance<KSClassDeclaration>()
            .forEach(::generateStore)

        return emptyList()
    }

    private fun generateSchema(marker: KSClassDeclaration) {
        val pkg = marker.packageName.asString()
        val lease = ClassName(pkg, "LotLease")
        val acquisition = ClassName(pkg, "LeaseAcquisition")
        val granted = acquisition.nestedClass("Granted")
        val denied = acquisition.nestedClass("Denied")
        val lotSet = SET.parameterizedBy(LOT_ID)
        val conflictMap = MAP.parameterizedBy(LOT_ID, JOB_ID)
        val jobSet = SET.parameterizedBy(JOB_ID)
        val result = TypeVariableName("R")
        val keepsLease = ClassName(pkg, "KeepsLease")

        val leaseType = TypeSpec.classBuilder(lease)
            .addModifiers(KModifier.PUBLIC, KModifier.DATA)
            .addKdoc("The lots a job has to itself while it runs.")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("job", JOB_ID)
                    .addParameter("lotIds", lotSet)
                    .build(),
            )
            .addProperty(PropertySpec.builder("job", JOB_ID).addModifiers(KModifier.PUBLIC).initializer("job").build())
            .addProperty(
                PropertySpec.builder("lotIds", lotSet).addModifiers(KModifier.PUBLIC).initializer("lotIds").build(),
            )
            .apply { marker.containingFile?.let(::addOriginatingKSFile) }
            .build()

        val acquisitionType = TypeSpec.interfaceBuilder(acquisition)
            .addModifiers(KModifier.PUBLIC, KModifier.SEALED)
            .addKdoc("What asking for a lease answered.")
            .addType(
                TypeSpec.classBuilder("Granted")
                    .addModifiers(KModifier.PUBLIC, KModifier.DATA)
                    .addSuperinterface(acquisition)
                    .primaryConstructor(FunSpec.constructorBuilder().addParameter("lease", lease).build())
                    .addProperty(
                        PropertySpec.builder("lease", lease).addModifiers(KModifier.PUBLIC).initializer("lease")
                            .build(),
                    )
                    .build(),
            )
            .addType(
                TypeSpec.classBuilder("Denied")
                    .addModifiers(KModifier.PUBLIC, KModifier.DATA)
                    .addSuperinterface(acquisition)
                    .primaryConstructor(FunSpec.constructorBuilder().addParameter("conflicts", conflictMap).build())
                    .addProperty(
                        PropertySpec.builder("conflicts", conflictMap)
                            .addModifiers(KModifier.PUBLIC)
                            .initializer("conflicts")
                            .build(),
                    )
                    .build(),
            )
            .apply { marker.containingFile?.let(::addOriginatingKSFile) }
            .build()

        val registryType = TypeSpec.classBuilder(ClassName(pkg, "LotLeaseRegistry"))
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
            .addKdoc("Who is allowed to touch which lots.")
            .addFunction(
                FunSpec.builder("tryReserve")
                    .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addKdoc("Reserves every lot in [lotIds] for [job], or nothing at all.")
                    .addParameter("job", JOB_ID)
                    .addParameter("lotIds", lotSet)
                    .returns(conflictMap)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("release")
                    .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addKdoc("Gives back everything [job] holds.")
                    .addParameter("job", JOB_ID)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("transfer")
                    .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addKdoc(
                        "Hands everything [from] holds to [to], in one step.\n\n" +
                                "@return the lots that moved.",
                    )
                    .addParameter("from", JOB_ID)
                    .addParameter("to", JOB_ID)
                    .returns(lotSet)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("reapAbandoned")
                    .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addKdoc(
                        "Frees every lease older than [maxAgeMillis] as of [nowMillis].\n\n" +
                                "The backstop for a job that died with the process still holding its lots.\n\n" +
                                "@return the jobs that were reaped.",
                    )
                    .addParameter("nowMillis", LONG)
                    .addParameter("maxAgeMillis", LONG)
                    .returns(jobSet)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("acquire")
                    .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                    .addKdoc(
                        "Asks for [lotIds] on behalf of [job].\n\n" +
                                "Granted or denied whole.",
                    )
                    .addParameter("job", JOB_ID)
                    .addParameter("lotIds", lotSet)
                    .returns(acquisition)
                    .addStatement("val conflicts = tryReserve(job, lotIds)")
                    .addStatement(
                        "return if (conflicts.isEmpty()) %T(%T(job, lotIds)) else %T(conflicts)",
                        granted,
                        lease,
                        denied,
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("extend")
                    .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                    .addKdoc("Grows [lease] to cover [lotIds] as well.")
                    .addParameter("lease", lease)
                    .addParameter("lotIds", lotSet)
                    .returns(acquisition)
                    .addStatement("val wanted = lease.lotIds + lotIds")
                    .addStatement("val conflicts = tryReserve(lease.job, wanted)")
                    .addStatement(
                        "return if (conflicts.isEmpty()) %T(%T(lease.job, wanted)) else %T(conflicts)",
                        granted,
                        lease,
                        denied,
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("holdingFor")
                    .addModifiers(KModifier.PUBLIC, KModifier.SUSPEND)
                    .addKdoc("Runs [block] and gives [job]'s lots back when it is over.")
                    .addTypeVariable(result)
                    .addParameter("job", JOB_ID)
                    .addParameter(
                        "block",
                        LambdaTypeName.get(returnType = result).copy(suspending = true),
                    )
                    .returns(result)
                    .beginControlFlow("val held = try")
                    .addStatement("block()")
                    .nextControlFlow("catch (failure: %T)", THROWABLE)
                    .addStatement("if (failure !is %T) release(job)", keepsLease)
                    .addStatement("throw failure")
                    .endControlFlow()
                    .addStatement("release(job)")
                    .addStatement("return held")
                    .build(),
            )
            .apply { marker.containingFile?.let(::addOriginatingKSFile) }
            .build()

        val keepsLeaseType = TypeSpec.interfaceBuilder(keepsLease)
            .addModifiers(KModifier.PUBLIC)
            .addKdoc("A failure that leaves the lease where it is.")
            .apply { marker.containingFile?.let(::addOriginatingKSFile) }
            .build()

        FileSpec.builder(pkg, "LotLeaseRegistry")
            .addType(keepsLeaseType)
            .addType(leaseType)
            .addType(acquisitionType)
            .addType(registryType)
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    private fun generateStore(klass: KSClassDeclaration) {
        val pkg = klass.packageName.asString()
        val name = "${klass.simpleName.asString()}Store"
        val entry = ClassName(pkg, name, "Entry")
        val holdersType = MUTABLE_MAP.parameterizedBy(LOT_ID, entry)
        val lotSet = SET.parameterizedBy(LOT_ID)
        val conflictMap = MAP.parameterizedBy(LOT_ID, JOB_ID)
        val jobSet = SET.parameterizedBy(JOB_ID)

        val type = TypeSpec.classBuilder(name)
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
            .superclass(ClassName(pkg, "LotLeaseRegistry"))
            .addKdoc("In-memory exclusive map.")
            .addProperty(
                PropertySpec.builder("writer", ClassName(pkg, "SingleWriterGuard"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("SingleWriterGuard()")
                    .build(),
            )
            .addType(
                TypeSpec.classBuilder("Entry")
                    .addModifiers(KModifier.PRIVATE, KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("job", JOB_ID)
                            .addParameter("acquiredAtMillis", LONG)
                            .build(),
                    )
                    .addProperty(PropertySpec.builder("job", JOB_ID).initializer("job").build())
                    .addProperty(PropertySpec.builder("acquiredAtMillis", LONG).initializer("acquiredAtMillis").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("holders", holdersType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("mutableMapOf()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("tryReserve")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("job", JOB_ID)
                    .addParameter("lotIds", lotSet)
                    .returns(conflictMap)
                    .addStatement("writer.checkIn()")
                    .addStatement(
                        "val conflicts = lotIds.mapNotNull { lotId ->\n" +
                                "    holders[lotId]?.takeIf { it.job != job }?.let { lotId to it.job }\n" +
                                "}.toMap()",
                    )
                    .addStatement("if (conflicts.isNotEmpty()) return conflicts")
                    .addStatement("val now = System.currentTimeMillis()")
                    .addStatement("for (lotId in lotIds) holders[lotId] = Entry(job, now)")
                    .addStatement("return emptyMap()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("release")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("job", JOB_ID)
                    .addStatement("writer.checkIn()")
                    .addStatement("holders.entries.removeAll { it.value.job == job }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("transfer")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("from", JOB_ID)
                    .addParameter("to", JOB_ID)
                    .returns(lotSet)
                    .addStatement("writer.checkIn()")
                    .addStatement("val now = System.currentTimeMillis()")
                    .addStatement("val toTransfer = holders.filterValues { it.job == from }.keys.toSet()")
                    .addStatement("for (lotId in toTransfer) holders[lotId] = Entry(to, now)")
                    .addStatement("return toTransfer")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("reapAbandoned")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("nowMillis", LONG)
                    .addParameter("maxAgeMillis", LONG)
                    .returns(jobSet)
                    .addStatement("writer.checkIn()")
                    .addStatement(
                        "val abandoned = holders.values\n" +
                                "    .filter { nowMillis - it.acquiredAtMillis > maxAgeMillis }\n" +
                                "    .mapTo(mutableSetOf()) { it.job }",
                    )
                    .addStatement("holders.entries.removeAll { it.value.job in abandoned }")
                    .addStatement("return abandoned")
                    .build(),
            )
            .apply { klass.containingFile?.let(::addOriginatingKSFile) }
            .build()

        FileSpec.builder(pkg, name)
            .addType(type)
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }
}
