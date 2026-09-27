package com.tracel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo

private const val OBSERVES_ANNOTATION = "com.tracel.annotations.Observes"

private val BUKKIT_LISTENER = ClassName("org.bukkit.event", "Listener")
private val BUKKIT_PLUGIN = ClassName("org.bukkit.plugin", "Plugin")
private val EVENT_PRIORITY = ClassName("org.bukkit.event", "EventPriority")
private val EVENT_EXECUTOR = ClassName("org.bukkit.plugin", "EventExecutor")

/** Turns `@Observes` functions into Bukkit `registerEvent` calls. */
public class ObservesProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()

        val functions = resolver.getSymbolsWithAnnotation(OBSERVES_ANNOTATION)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()
        if (functions.isEmpty()) return emptyList()

        val handlers = functions.mapNotNull(::handlerFor)
        if (handlers.size != functions.size) return emptyList()

        write(handlers.groupBy { it.owner }.toSortedMap(compareBy { it.qualifiedName!!.asString() }))
        written = true
        return emptyList()
    }

    private class Handler(
        val owner: KSClassDeclaration,
        val function: String,
        val eventType: ClassName,
        val priority: String,
        val ignoreCancelled: Boolean,
    )

    private fun handlerFor(function: KSFunctionDeclaration): Handler? {
        val name = function.simpleName.asString()
        val owner = function.parentDeclaration as? KSClassDeclaration
        if (owner?.qualifiedName == null) {
            return logger.fail("@Observes can only annotate a member function of a class.", function)
        }
        if (Modifier.PRIVATE in function.modifiers) {
            return logger.fail(
                "@Observes function \"$name\" must not be private.",
                function,
            )
        }
        val parameter = function.parameters.singleOrNull()
            ?: return logger.fail("@Observes function \"$name\" must take exactly one parameter, the event.", function)

        val eventDecl = parameter.type.resolve().declaration.qualifiedName
            ?: return logger.fail(
                "@Observes function \"$name\" takes a parameter whose type cannot be resolved.",
                function
            )

        val annotation = function.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == OBSERVES_ANNOTATION
        }
        return Handler(
            owner = owner,
            function = name,
            eventType = ClassName.bestGuess(eventDecl.asString()),
            priority = annotation.enumArgument("priority") ?: "MONITOR",
            ignoreCancelled = annotation.argument("ignoreCancelled") as? Boolean ?: true,
        )
    }

    private fun write(byClass: Map<KSClassDeclaration, List<Handler>>) {
        val register = FunSpec.builder("registerObserved")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("listener", BUKKIT_LISTENER)
            .addParameter("plugin", BUKKIT_PLUGIN)
            .returns(INT)
            .addKdoc(
                """
                Registers every `@Observes` handler on [listener], and says how many there were.

                Zero means the listener has no handlers at all.
                """.trimIndent(),
            )
            .addStatement("val manager = plugin.server.pluginManager")
            .beginControlFlow("return when (listener)")
            .apply {
                for ((owner, handlers) in byClass) {
                    owner.containingFile?.let(::addOriginatingKSFile)
                    val ownerName = ClassName.bestGuess(owner.qualifiedName!!.asString())
                    beginControlFlow("is %T ->", ownerName)
                    for (handler in handlers.sortedBy { it.function }) {
                        addCode(registerCall(handler))
                    }
                    addStatement("%L", handlers.size)
                    endControlFlow()
                }
                addStatement("else -> 0")
            }
            .endControlFlow()
            .build()

        FileSpec.builder("com.tracel.plugin.listener.api", "ObservedEvents")
            .addFunction(register)
            .build()
            .writeTo(codeGenerator, aggregating = true)
    }

    private fun registerCall(handler: Handler): CodeBlock = CodeBlock.builder()
        .addStatement(
            """
            manager.registerEvent(
                %T::class.java,
                listener,
                %T.%L,
                %T { _, event ->
                    if (event is %T) listener.%L(event)
                },
                plugin,
                %L,
            )
            """.trimIndent(),
            handler.eventType,
            EVENT_PRIORITY,
            handler.priority,
            EVENT_EXECUTOR,
            handler.eventType,
            handler.function,
            handler.ignoreCancelled,
        )
        .build()

    private fun KSAnnotation.argument(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value

    private fun KSAnnotation.enumArgument(name: String): String? = when (val value = argument(name)) {
        null -> null
        is KSType -> value.declaration.simpleName.asString()
        is KSClassDeclaration -> value.simpleName.asString()
        else -> value.toString().substringAfterLast('.')
    }
}
