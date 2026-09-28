// Pure JVM on purpose (ADR-0075): the navigation maths must be testable in a
// second, with no device and no emulator, so it carries no Android types.
plugins { id("org.jetbrains.kotlin.jvm") }

/**
 * 17, matching the `app` module — NOT 21.
 *
 * ## What 21 did
 *
 * This module is consumed by an Android application whose `compileOptions` and
 * `kotlinOptions.jvmTarget` are both 17, and whose unit tests run on whatever
 * JVM launches Gradle. `jvmToolchain(21)` emits **class file version 65**, and
 * a Java 17 runtime refuses to load it:
 *
 *     java.lang.UnsupportedClassVersionError: dev/vector/geo/LngLat has been
 *     compiled by a more recent version of the Java Runtime (class file
 *     version 65.0), this version of the Java Runtime only recognizes class
 *     file versions up to 61.0
 *
 * Measured on this host: **151 of 479** `:app:testDebugUnitTest` cases failed
 * that way — every case that so much as mentions a `core-geo` type, which is
 * most of the navigation suite. `:core-geo:test` stayed green throughout,
 * because in isolation it both compiles and runs on 21, which is exactly why
 * this went unnoticed: the module that is wrong is the one that passes.
 *
 * ## Why it was ever green
 *
 * It is environment-dependent, not a code change. When Gradle is launched by a
 * JDK 21 the app's test JVM is also 21 and can read version-65 classes; when it
 * is launched by a JDK 17 — which is this machine, and which is the JDK the
 * `compileOptions` above are written for — it cannot. The suite's result
 * therefore depended on which `java` happened to be first on PATH, and
 * `.scratch/vector-product/GOALS.md` already recorded this class of drift
 * ("this is not the machine the previous plans were written for": JDK claimed
 * 17, measured 25).
 *
 * ## Why 17 rather than raising the app
 *
 * A library must not emit bytecode newer than the application that links it.
 * Android's toolchain is the constraint here — `compileSdk 35` with
 * `VERSION_17` — so 17 is the contract, and pinning the library to it makes
 * the suite's outcome independent of which JDK starts the daemon. Nothing in
 * this module uses a language feature above 17.
 */
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Perf budgets are only useful if you can see the number they passed with.
    testLogging { showStandardStreams = true }
}
