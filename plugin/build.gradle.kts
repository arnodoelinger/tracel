plugins {
    id("tracel.kotlin-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":model"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.caffeine)
    implementation(libs.semver)
    implementation(libs.tomlj)
    ksp(project(":codegen"))

    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(project(":tests"))
    testImplementation(libs.sqlite.jdbc)
}

tasks {
    shadowJar {
        archiveBaseName.set("Tracel")
        archiveClassifier.set("")

        relocate("com.github.benmanes.caffeine", "com.tracel.shaded.caffeine")
        relocate("io.github.z4kn4fein.semver", "com.tracel.shaded.semver")
        relocate("org.tomlj", "com.tracel.shaded.tomlj")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    // Developer server on the lowest supported platform version, so anything that only
    // exists in 26.2 fails here rather than in production.
    runServer {
        minecraftVersion("26.1.2")
        jvmArgs(
            "-Xms6G",
            "-Xmx6G",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:+AlwaysPreTouch",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints"
        )
    }
}

runPaper {
    folia {
        registerTask()
    }
}
