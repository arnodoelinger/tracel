plugins {
    id("tracel.pure-kotlin")
    id("java-test-fixtures")
}

dependencies {
    api(project(":model"))
    api(project(":engine"))
    api(project(":platform"))
    api(libs.kotlinx.coroutines.test)
    api(libs.konsist)
    api(libs.hdrhistogram)
}
