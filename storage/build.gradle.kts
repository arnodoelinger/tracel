plugins {
    id("tracel.kotlin-conventions")
}

dependencies {
    implementation(project(":model"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.caffeine)
    implementation(libs.zstd)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hdrhistogram)
    testImplementation(testFixtures(project(":tests")))
}
