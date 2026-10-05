import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

private val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
        freeCompilerArgs.add("-Xcollection-literals")
    }
}

dependencies {
    add("testImplementation", platform(versions.findLibrary("junit-bom").get()))
    add("testImplementation", versions.findLibrary("junit-jupiter").get())
    add("testRuntimeOnly", versions.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    System.getProperties().forEach { (key, value) ->
        val name = key.toString()
        if (!name.startsWith("tracel.")) return@forEach
        systemProperty(name, value.toString())
        inputs.property(name, value.toString())
    }
    testLogging {
        events("failed")
        showStandardStreams = System.getProperty("tracel.bench") != null
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
