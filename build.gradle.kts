import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

group = "com.mutterboard"
version = "1.0.0"

// sherpa-onnx Java API + JNI natives, used to keep the offline Parakeet model
// warm in-process (a subprocess pays ~1.4s of model load per dictation). Not
// on Maven Central, so the jars are fetched from the GitHub release at build
// time and cached in build/sherpa-jars.
val sherpaVersion = "1.13.4"
val sherpaJarDir = layout.buildDirectory.dir("sherpa-jars")
val fetchSherpaJars = tasks.register("fetchSherpaJars") {
    outputs.dir(sherpaJarDir)
    doLast {
        val dir = sherpaJarDir.get().asFile.apply { mkdirs() }
        listOf(
            "sherpa-onnx-v$sherpaVersion.jar",
            "sherpa-onnx-native-lib-linux-x64-v$sherpaVersion.jar",
        ).forEach { name ->
            val dest = File(dir, name)
            if (!dest.exists()) {
                URI(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$name",
                ).toURL().openStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
    }
}

dependencies {
    implementation(fileTree(sherpaJarDir) { include("*.jar") }.builtBy(fetchSherpaJars))
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
            // jlink builds a minimal runtime; these modules aren't auto-detected
            // because dbus-java loads them reflectively for the global hotkey
            // (com.sun.security.auth.module.UnixSystem lives in jdk.security.auth).
            // From `./gradlew suggestRuntimeModules`.
            modules("java.instrument", "jdk.security.auth", "jdk.unsupported")
            packageName = "Mutterboard"
            packageVersion = "1.0.0"
            description = "Voice dictation: Whisper V3 Turbo + Llama refiner, straight to your clipboard."
        }
    }
}

// One-command local install: build the self-contained image, drop it in
// ~/.local/opt (so it survives `gradle clean`), and register a .desktop entry
// in the app menu. Bazzite is immutable, so everything lives under ~/.local.
// Run: ./gradlew installDesktop
tasks.register("installDesktop") {
    group = "distribution"
    description = "Build the app image and install it into the local application menu."
    dependsOn("createDistributable")
    doLast {
        val home = System.getProperty("user.home")
        val appImage = layout.buildDirectory
            .dir("compose/binaries/main/app/Mutterboard").get().asFile
        val appDest = file("$home/.local/opt/Mutterboard")
        val iconName = "mutterboard-icon.png"

        // 1. Install the self-contained image to a stable location.
        delete(appDest)
        copy {
            from(appImage)
            into(appDest)
        }
        File(appDest, "bin/Mutterboard").setExecutable(true)

        // 2. Icon next to the app, referenced by absolute path in the entry.
        copy {
            from("src/main/resources/$iconName")
            into(appDest)
        }

        // 3. Desktop entry (single main Category to avoid duplicate menu items).
        val appsDir = file("$home/.local/share/applications").apply { mkdirs() }
        val desktopFile = File(appsDir, "mutterboard.desktop")
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Mutterboard
            GenericName=Voice Dictation
            Comment=Voice dictation: Whisper V3 Turbo + Llama refiner, straight to your clipboard.
            Exec=$appDest/bin/Mutterboard
            Icon=$appDest/$iconName
            Terminal=false
            Categories=Utility;
            Keywords=dictation;voice;transcription;whisper;speech;
            StartupWMClass=Mutterboard
            """.trimIndent() + "\n"
        )

        // 4. Refresh the menu database (best-effort; harmless if absent).
        runCatching {
            ProcessBuilder("update-desktop-database", appsDir.absolutePath)
                .inheritIO().start().waitFor()
        }
        println("Installed Mutterboard -> $appDest")
        println("Registered menu entry -> ${desktopFile.absolutePath}")
    }
}
