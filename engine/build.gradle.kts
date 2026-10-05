plugins {
    id("tracel.pure-kotlin")
    id("tracel.serialization")
    alias(libs.plugins.atomicfu)
}

dependencies {
    api(project(":model"))
    api(project(":platform"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.kotlinx.coroutines.test)
}
