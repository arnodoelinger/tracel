plugins {
    id("tracel.pure-kotlin")
    alias(libs.plugins.atomicfu)
}

dependencies {
    api(project(":model"))
    api(project(":platform"))
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.kotlinx.coroutines.test)
}
