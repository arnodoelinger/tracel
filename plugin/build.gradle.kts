plugins {
    id("tracel.kotlin-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":model"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)

    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(project(":tests"))
}

tasks {
    shadowJar {
        archiveBaseName.set("Tracel")
        archiveClassifier.set("")

        relocate("com.zaxxer.hikari", "com.tracel.shaded.hikari")
        relocate("org.jetbrains.exposed", "com.tracel.shaded.exposed")
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
        jvmArgs("-Xms2G", "-Xmx2G")
    }
}

runPaper {
    folia {
        registerTask()
    }
}
