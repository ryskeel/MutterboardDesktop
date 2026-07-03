import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

group = "com.mutterboard"
version = "0.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Same HTTP + JSON stack the Android app uses, so GroqWhisperClient and
    // GroqRefiner port over almost verbatim. org.json is bundled on Android but
    // must be an explicit dependency on the desktop JVM.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20250107")
    // D-Bus client for the XDG GlobalShortcuts portal (global hotkey on Wayland).
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

compose.desktop {
    application {
        mainClass = "com.mutterboard.MainKt"
        nativeDistributions {
            // Deb for Debian/Ubuntu, plus a self-contained image. Bazzite's
            // Flatpak packaging comes later; these prove local packaging works.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Mutterboard"
            packageVersion = "0.1.0"
            description = "Voice dictation: Whisper V3 Turbo + Llama refiner, straight to your clipboard."
        }
    }
}
