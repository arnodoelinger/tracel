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

    compileOnly(libs.fastutil)

    testImplementation(libs.fastutil)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hdrhistogram)
    testImplementation(testFixtures(project(":tests")))
}
