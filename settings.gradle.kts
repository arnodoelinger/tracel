@file:Suppress("UnstableApiUsage")

rootProject.name = "Tracel"

pluginManagement {
    includeBuild("gradle")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://maven.enginehub.org/repo/") {
            name = "enginehub"
        }
    }
}

include(
    "model",
    "engine",
    "platform",
    "tests",
    "annotations",
    "storage",
    "plugin",
    "codegen",
)
