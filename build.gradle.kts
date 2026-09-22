// =============================================================================
// Kotlin example code for "Analysis of Inventory Systems".
//
// Standalone Gradle project, deliberately NOT part of the Quarto book build.
// `quarto render` neither builds nor needs this; the book references the code,
// and the code does not reference the book.
//
//   cd code && ./gradlew run          # runs the default example
//   cd code && ./gradlew build        # compiles everything
//
// THIS DIRECTORY IS THE PUBLIC ONE. Everything here is intended to be handed to
// students as a standalone Gradle project alongside the rendered book. Nothing
// instructor-only may live here, not even in a source set that the jar excludes,
// because what goes public is the SOURCE. Worked exercise solutions live in
// ../solutions/code, which is a separate Gradle project that includes this one.
// scripts/check-solutions.sh fails if solution material appears under src/.
// =============================================================================
plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "edu.uark.inventory"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // The Kotlin Simulation Library, from Maven Central. Matches the version
    // documented in the KSL repository's README.
    implementation("io.github.rossetti:KSLCore:R1.7")
    testImplementation(kotlin("test"))
}

kotlin {
    // KSL targets JVM 21; match it so the toolchains agree.
    jvmToolchain(21)
}

application {
    // Override from the command line, for example
    //   ./gradlew run -PmainClass=inventory.lotsizing.Chapter3ExamplesKt
    mainClass.set(
        providers.gradleProperty("mainClass").orElse("inventory.newsvendor.NewsvendorMonteCarloKt")
    )
}

tasks.test {
    useJUnitPlatform()
}

