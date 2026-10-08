import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    id("tracel.kotlin-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.hangar.publish)
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
    implementation(libs.bstats)
    ksp(project(":codegen"))

    compileOnly(libs.paper.api)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.fawe.core) { exclude(group = "net.kyori") }
    testImplementation(libs.paper.api)
    testImplementation(testFixtures(project(":tests")))
    testImplementation(libs.sqlite.jdbc)
}

tasks {
    shadowJar {
        archiveBaseName.set("Tracel")
        archiveClassifier.set("")

        relocate("com.github.benmanes.caffeine", "com.tracel.shaded.caffeine")
        relocate("io.github.z4kn4fein.semver", "com.tracel.shaded.semver")
        relocate("org.tomlj", "com.tracel.shaded.tomlj")
        relocate("org.bstats", "com.tracel.shaded.bstats")
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

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        id.set("Tracel")
        channel.set(providers.environmentVariable("HANGAR_CHANNEL").orElse("Snapshot"))
        changelog.set(providers.environmentVariable("CHANGELOG_FILE").map { file(it).readText() }.orElse(""))
        apiKey.set(providers.environmentVariable("HANGAR_API_TOKEN"))
        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                platformVersions.set(listOf("26.1", "26.1.1", "26.1.2", "26.2", "26.3"))
                dependencies {
                    url("WorldEdit", "https://enginehub.org/worldedit") { required.set(false) }
                    url("FastAsyncWorldEdit", "https://www.spigotmc.org/resources/13932/") { required.set(false) }
                    url("LuckPerms", "https://luckperms.net") { required.set(false) }
                }
            }
        }
    }
}
