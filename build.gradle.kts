plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "searchsimulation"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("searchsimulation.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
