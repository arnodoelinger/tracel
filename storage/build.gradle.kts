plugins {
    id("tracel.kotlin-conventions")
    id("tracel.serialization")
}

dependencies {
    implementation(project(":model"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.hikaricp)

    compileOnly(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":tests")))
}
