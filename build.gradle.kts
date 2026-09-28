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

// `./gradlew run` = offline batch snapshot generator (BuildSite.kt), unchanged.
// `./gradlew runServer` = the local live server the page's Update button calls (Server.kt).
// No new dependencies for this -- Server.kt uses the JDK's own com.sun.net.httpserver.HttpServer.
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Starts the local live server (fetch+rescore on demand, what the page's Update button calls)."
    mainClass.set("ranker.ServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
