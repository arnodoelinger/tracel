plugins {
    id("tracel.pure-kotlin")
}

dependencies {
    api(project(":model"))
    api(libs.kotlinx.coroutines.core)
}
