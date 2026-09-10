package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

private const val ASSUMPTION_ANNOTATION = "com.tracel.annotations.Assumption"
private const val FALLBACK_ANNOTATION = "com.tracel.annotations.Fallback"

/**
 * Turns an `@Assumption` / `@Fallback("...")`-annotated function into a generated
 * `<name>Guarded` dispatcher extension function, next to an id constant it switches on.
 */
public class AssumptionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val functions = resolver.getSymbolsWithAnnotation(ASSUMPTION_ANNOTATION)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        functions.groupBy { it.parentDeclaration as? KSClassDeclaration }.forEach { (classDecl, funcsInClass) ->
            if (classDecl == null) {
                funcsInClass.forEach {
                    logger.error("@Assumption can only annotate a member function of a class.", it)
                }
                return@forEach
            }
            generateFor(classDecl, funcsInClass)
        }
        return emptyList()
    }

    private fun generateFor(classDecl: KSClassDeclaration, functions: List<KSFunctionDeclaration>) {
        val specs = functions.mapNotNull { guardedSpecs(classDecl, it) }
        if (specs.size != functions.size) return

        val file = FileSpec.builder(classDecl.packageName.asString(), "${classDecl.simpleName.asString()}Assumptions")
        for ((id, fn) in specs) {
            file.addProperty(id)
            file.addFunction(fn)
        }
        // New -> stream.bufferedWriter().use
        file.build().writeTo(codeGenerator, aggregating = false)
    }

    private fun guardedSpecs(classDecl: KSClassDeclaration, function: KSFunctionDeclaration): Pair<PropertySpec, FunSpec>? {
        val className = classDecl.simpleName.asString()
        val funcName = function.simpleName.asString()

        if (Modifier.PRIVATE in function.modifiers) {
            return logger.fail(
                "@Assumption function '$funcName' must be internal.",
                function,
            )
        }

        val fallbackAnnotation = function.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == FALLBACK_ANNOTATION
        } ?: return logger.fail(
            "@Assumption function '$funcName' is missing a paired @Fallback annotation.",
            function,
        )

        val fallback = fallbackAnnotation.arguments.firstOrNull { it.name?.asString() == "to" }?.value as? String
            ?: return logger.fail("@Fallback on \"$funcName\" is missing \"to\".", function)

        val fallbackExists = classDecl.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .any { it.simpleName.asString() == fallback }
        if (!fallbackExists) {
            return logger.fail("@Fallback \"$fallback\" not found as a function on $className.", function)
        }

        val parameters = function.parameters.map { param ->
            val name = param.name?.asString() ?: return logger.fail(
                "@Assumption function \"$funcName\" has an unnamed parameter.",
                function,
            )
            name to param.type.toTypeName()
        }

        val idName = "${className}_${funcName}AssumptionId"
        val idValue = "$className.$funcName"
        val owner = ClassName(classDecl.packageName.asString(), className)

        val id = PropertySpec.builder(idName, STRING)
            .addModifiers(KModifier.INTERNAL, KModifier.CONST)
            .initializer("%S", idValue)
            .apply { classDecl.containingFile?.let(::addOriginatingKSFile) }
            .build()

        val guarded = FunSpec.builder("${funcName}Guarded")
            .addModifiers(KModifier.INTERNAL)
            .receiver(owner)
            .apply {
                parameters.forEach { (name, type) -> addParameter(name, type) }
                classDecl.containingFile?.let(::addOriginatingKSFile)
            }
            .beginControlFlow("if (services.vanillaAssumptions.isTripped(%N))", idName)
            .addStatement("%N(%L)", fallback, parameters.joinToString { it.first })
            .nextControlFlow("else")
            .addStatement("%N(%L)", funcName, parameters.joinToString { it.first })
            .endControlFlow()
            .build()

        return id to guarded
    }
}
