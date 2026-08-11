
plugins {
    id("tracel.kotlin-conventions")
}

kotlin {
    explicitApi()
}

val verifyNoBukkit = tasks.register<VerifyNoBukkit>("verifyNoBukkit") {
    group = "verification"
    description = "Fails if a Minecraft platform dependency leaks into a pure-Kotlin module."

    moduleName.set(project.name)
    rootComponent.set(
        configurations.named("compileClasspath").flatMap {
            it.incoming.resolutionResult.rootComponent
        }
    )
}

tasks.named("check") {
    dependsOn(verifyNoBukkit)
}
