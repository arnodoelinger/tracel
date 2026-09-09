package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MUTABLE_MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo

private const val LEASE = "com.tracel.annotations.Lease"
private const val LEASE_STORE = "com.tracel.annotations.LeaseStore"

private val LOT_ID = ClassName("com.tracel.model.id", "LotId")
private val JOB_ID = ClassName("com.tracel.model.id", "RollbackJobId")

/** Emits the in-memory store. */
public class LeaseProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()
        written = true

        if (resolver.getSymbolsWithAnnotation(LEASE).none()) {
            logger.warn("@Lease marker is unused; LotLease types should live in main sources.")
        }

        resolver.getSymbolsWithAnnotation(LEASE_STORE)
            .filterIsInstance<KSClassDeclaration>()
            .forEach(::generateStore)

        return emptyList()
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
