plugins {
    id("tracel.pure-kotlin")
}

dependencies {
    api(project(":model"))
    api(project(":engine"))
    api(project(":platform"))
    api(libs.kotlinx.coroutines.test)
    api(libs.konsist)
}
