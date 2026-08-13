package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import java.io.Writer

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
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val sourceFile = classDecl.containingFile

        val dependencies = if (sourceFile != null) {
            Dependencies(aggregating = false, sourceFile)
        } else {
            Dependencies(aggregating = false)
        }

        val stream = codeGenerator.createNewFile(
            dependencies,
            packageName,
            "${className}Assumptions",
        )

        stream.bufferedWriter().use { writer ->
            writer.write("package $packageName\n\n")

            for (function in functions) {
                if (!renderGuarded(writer, classDecl, function)) return
            }
        }
    }

    private fun renderGuarded(writer: Writer, classDecl: KSClassDeclaration, function: KSFunctionDeclaration): Boolean {
        val className = classDecl.simpleName.asString()
        val funcName = function.simpleName.asString()

        if (Modifier.PRIVATE in function.modifiers) {
            logger.error(
                "@Assumption function '$funcName' must be internal, not private — the generated " +
                    "dispatcher lives in a different file in the same module and can't see private members.",
                function,
            )
            return false
        }

        val fallbackAnnotation = function.annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == FALLBACK_ANNOTATION }
        if (fallbackAnnotation == null) {
            logger.error("@Assumption function '$funcName' is missing a paired @Fallback annotation.", function)
            return false
        }
        val fallback = fallbackAnnotation.arguments.firstOrNull { it.name?.asString() == "to" }?.value as? String
        if (fallback == null) {
            logger.error("@Fallback on \"$funcName\" is missing \"to\".", function)
            return false
        }

        val fallbackFunction = classDecl.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .firstOrNull { it.simpleName.asString() == fallback }
        if (fallbackFunction == null) {
            logger.error("@Fallback \"$fallback\" not found as a function on $className.", function)
            return false
        }

        val params = function.parameters.map { param ->
            val name = param.name?.asString() ?: run {
                logger.error("@Assumption function \"$funcName\" has an unnamed parameter.", function)
                return false
            }
            name to renderType(param.type.resolve())
        }

        val paramList = params.joinToString(", ") { (name, type) -> "$name: $type" }
        val argList = params.joinToString(", ") { (name, _) -> name }
        val idConstant = "${className}_${funcName}AssumptionId"

        writer.write("internal const val $idConstant: String = \"$className.$funcName\"\n\n")
        writer.write("internal fun $className.${funcName}Guarded($paramList) {\n")
        writer.write("    if (services.vanillaAssumptions.isTripped($idConstant)) {\n")
        writer.write("        $fallback($argList)\n")
        writer.write("    } else {\n")
        writer.write("        $funcName($argList)\n")
        writer.write("    }\n")
        writer.write("}\n\n")
        return true
    }

    private fun renderType(type: KSType): String {
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        val arguments = type.arguments
        val argumentsString = if (arguments.isEmpty()) {
            ""
        } else {
            "<" + arguments.joinToString(", ") { arg -> arg.type?.resolve()?.let { renderType(it) } ?: "*" } + ">"
        }
        val nullability = if (type.isMarkedNullable) "?" else ""
        return "$qualifiedName$argumentsString$nullability"
    }
}
