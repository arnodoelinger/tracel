plugins {
    id("tracel.pure-kotlin")
    id("tracel.serialization")
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.ksp)
}

dependencies {
    api(project(":model"))
    api(project(":platform"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    ksp(project(":codegen"))

    testImplementation(libs.kotlinx.coroutines.test)
}
