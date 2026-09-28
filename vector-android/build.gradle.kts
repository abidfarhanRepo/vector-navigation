plugins {
    id("com.android.application") version "8.13.0" apply false
    // The macrobenchmark module (`:benchmark`) is a `com.android.test` module:
    // AGP builds a test APK from it that is installed alongside `:app` and
    // drives it out-of-process. Same AGP as `:app`, declared here with the rest
    // so every Android plugin in this build has exactly one version in one place.
    id("com.android.test") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // Required from Kotlin 2.0 whenever compose is enabled.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
