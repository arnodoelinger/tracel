plugins {
    id("tracel.pure-kotlin")
    `maven-publish`
}

kotlin {
    compilerOptions {
        optIn.addAll("com.tracel.api.Unstable", "com.tracel.api.Internal")
    }
}

java {
    withSourcesJar()
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.paper.api)
    testImplementation(libs.kotlinx.coroutines.core)
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "tracel-api"
            from(components["java"])
            pom {
                name.set("Tracel API")
                description.set("Public API for integrating with Tracel.")
            }
        }
    }
}
