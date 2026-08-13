plugins {
    id("tracel.pure-kotlin")
}

dependencies {
    implementation(project(":annotations"))
    implementation(libs.symbol.processing.api)
}
