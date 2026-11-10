# MeshLink 2.0 — Gradle catalog and wrapper audit

## Result

The `gradle/libs.versions.toml` file has been reconstructed and included in this package.
All version-catalog aliases referenced by the root and app Gradle Kotlin scripts are declared.
All `version.ref` values resolve to an entry in `[versions]`.

## Project layout

- Root project: `MeshLink`
- Included module: `:app`
- App build file: `app/build.gradle.kts`
- Root build file: `build.gradle.kts`
- Version catalog: `gradle/libs.versions.toml`
- Wrapper JAR/scripts: `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`

## Alias audit

Root plugin aliases:
- `libs.plugins.android.application`
- `libs.plugins.kotlin.compose`
- `libs.plugins.ksp`
- `libs.plugins.hilt`

App plugin aliases:
- `libs.plugins.android.application`
- `libs.plugins.kotlin.compose`
- `libs.plugins.ksp`
- `libs.plugins.hilt`

App library aliases:
- `libs.androidx.compose.bom`
- `libs.androidx.activity.compose`
- `libs.androidx.compose.material3`
- `libs.androidx.compose.ui`
- `libs.androidx.compose.ui.graphics`
- `libs.androidx.compose.ui.tooling.preview`
- `libs.androidx.compose.material.icons.extended`
- `libs.androidx.core.ktx`
- `libs.androidx.lifecycle.runtime.ktx`
- `libs.kotlinx.coroutines.android`
- `libs.androidx.work.runtime.ktx`
- `libs.androidx.room.runtime`
- `libs.androidx.room.ktx`
- `libs.androidx.room.compiler`
- `libs.hilt.android`
- `libs.hilt.compiler`
- `libs.junit`
- `libs.androidx.compose.ui.test.junit4`
- `libs.androidx.espresso.core`
- `libs.androidx.junit`
- `libs.androidx.compose.ui.test.manifest`
- `libs.androidx.compose.ui.tooling`

## Pinned versions retained from the existing project

- Android Gradle Plugin: `9.4.1`
- Gradle wrapper: `9.6.0`
- Kotlin / Compose compiler plugin: `2.4.20`
- KSP: `2.3.12`
- Hilt: `2.60.1`
- Compose BOM: `2026.02.01`
- Room: `2.8.5`
- AndroidX Core KTX: `1.16.0`
- Activity Compose: `1.10.1`
- Lifecycle Runtime KTX: `2.9.3`
- Coroutines: `1.10.2`
- WorkManager: `2.10.1`
- JUnit: `4.13.2`
- AndroidX Test JUnit: `1.3.0`
- Espresso: `3.7.0`

No dependency versions were arbitrarily upgraded or downgraded during this repair. Compose UI, Material 3, tooling, and test artifacts without direct versions are governed by the Compose BOM.

## Java and Gradle daemon

`gradle/gradle-daemon-jvm.properties` pins the Gradle daemon toolchain to Java `25`.
The Gradle wrapper is pinned to `9.6.0` and includes a distribution SHA-256.
The app's Java source and target compatibility are both Java `17`; this is the bytecode/source target and is separate from the JDK that runs Gradle.

The Android Gradle Plugin 9.4 line requires Gradle 9.6.0 and JDK 17 or newer. Gradle's Java compatibility table supports running Gradle on Java 25 from Gradle 9.1 onward. Thus the configured daemon JDK 25 and wrapper Gradle 9.6.0 satisfy those minimums.

**Compatibility caveat:** Kotlin's published fully-supported AGP matrix for Kotlin 2.4.20 currently lists AGP through 9.3.1. This project retains AGP 9.4.1 and Kotlin 2.4.20 because those are its existing pinned versions and the requested repair was not a dependency upgrade exercise. This pair is outside Kotlin's explicitly listed fully-supported AGP range; a real Gradle configuration/build is still required to prove it works together. Do not interpret the static alias audit as a successful build.

## Build configuration

`settings.gradle.kts` sets `rootProject.name = "MeshLink"` and includes `:app`.
Plugin repositories are configured under `pluginManagement`; dependency repositories are Google and Maven Central under `dependencyResolutionManagement`.
`app/build.gradle.kts` is the only Android application module build file.

## Verification boundary

Static checks performed:
- TOML parsed successfully.
- Every `libs.*` and `libs.plugins.*` reference in Gradle scripts maps to a declared catalog alias.
- Every catalog `version.ref` resolves.
- Root/module paths and wrapper files are present.

Attempted:
`./gradlew --offline help`

It failed before Gradle startup because the Gradle 9.6.0 distribution was not cached and this environment could not resolve `services.gradle.org` (`UnknownHostException`). Therefore Gradle configuration, dependency resolution, compilation, tests, and APK generation remain unverified in this environment.
