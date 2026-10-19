plugins {
    id("tracel.pure-kotlin")
}

kotlin {
    compilerOptions {
        optIn.addAll("com.tracel.api.Unstable", "com.tracel.api.Internal")
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.paper.api)
    testImplementation(libs.kotlinx.coroutines.core)
}
