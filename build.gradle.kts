// Top-level build file - plugin versions are declared here (with apply false)
// and actually applied per-module in app/build.gradle.kts. Bump these via
// Android Studio's "Upgrade Assistant" rather than by hand once the project
// is open there - AGP/Kotlin/Compose compiler versions are tightly coupled.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
