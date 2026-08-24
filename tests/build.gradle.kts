import org.gradle.api.tasks.PathSensitivity

plugins {
    id("tracel.pure-kotlin")
    id("java-test-fixtures")
}

dependencies {
    api(project(":model"))
    api(project(":engine"))
    api(project(":platform"))
    api(libs.kotlinx.coroutines.test)
    api(libs.konsist)
    api(libs.hdrhistogram)
}

tasks.test {
    inputs.files(
        rootProject.layout.projectDirectory.asFileTree.matching {
            include("*/src/**/*.kt")
            exclude("**/build/**")
        }
    ).withPropertyName("projectSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
