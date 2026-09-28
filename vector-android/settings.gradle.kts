pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "vector-android"
include(":core-geo")
include(":app")
// The macrobenchmark module. Test-only: it produces an instrumentation APK that
// drives `:app`, and is never part of a release artefact. See
// `benchmark/README.md` for how to run it.
include(":benchmark")
