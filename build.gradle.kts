plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ranker.BuildSiteKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
