plugins {
    id("tracel.pure-kotlin")
    id("tracel.serialization")
}

dependencies {
    api(project(":model"))
    api(project(":platform"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
