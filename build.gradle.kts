plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "darkan"
version = "1.0.0"
description = "Darkan voice/overhead-icon sync API server"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.13"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    // Pure-Java Opus codec (no native deps) — compress the mic/voice hops.
    implementation("io.github.jaredmdobson:concentus:1.0.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("VoiceServerKt")
}

tasks.shadowJar {
    archiveBaseName.set("ktor-voice")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes(mapOf("Main-Class" to "VoiceServerKt"))
    }
}
