import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.compose") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.0"
    application
}

group = "com.zerovpn"
version = "1.5.1"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    // Windows Skia natives so the fat jar runs on Windows too
    implementation(compose.desktop.windows_x64)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("com.zerovpn.desktop.MainKt")
}

// Cross-platform fat jar: contains Linux (dev) + Windows natives.
val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("ZeroVPN")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.zerovpn.desktop.MainKt" }
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

// Windows-only fat jar for shipping (drops Linux/macOS Skia natives)
val windowsJar = tasks.register<Jar>("windowsJar") {
    archiveBaseName.set("ZeroVPN")
    archiveClassifier.set("windows")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.zerovpn.desktop.MainKt" }
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("libskiko-linux-*.so*", "skiko-linux*", "libskiko-macos*", "skiko-macos*", "skiko-js*")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

tasks.register("buildWindowsJar") {
    dependsOn(fatJar)
}
