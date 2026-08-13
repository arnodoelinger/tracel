package com.tracel.codegen

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Provides the [AssumptionProcessor] to KSP. */
public class AssumptionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        AssumptionProcessor(environment.codeGenerator, environment.logger)
}
