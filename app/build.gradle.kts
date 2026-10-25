import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The upload key for Google Play, from Gradle properties kept outside the repository — `~/.gradle/gradle.properties` or `-P`
// (`play-release` D1, docs/play-release.md). A PKCS12 store has one password, for the store and the key.
val uploadProperties = listOf("storeFile", "password", "keyAlias").map { "spekterm.upload.$it" }
val upload = uploadProperties.associateWith { providers.gradleProperty(it).orNull }
val uploadMissing = upload.filterValues { it.isNullOrBlank() }.keys.toList()

android {
    namespace = "com.spekterm.android"
    compileSdk = 37

    defaultConfig {
        // Frozen once published on Google Play.
        applicationId = "com.spekterm.android"
        minSdk = 29
        targetSdk = 36
        // Raised for every upload to Google Play, versionName never reused (docs/play-release.md, `play-release` D2).
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadMissing.isEmpty()) {
            create("upload") {
                storeFile = file(upload.getValue("spekterm.upload.storeFile")!!)
                storePassword = upload["spekterm.upload.password"]
                keyAlias = upload["spekterm.upload.keyAlias"]
                keyPassword = upload["spekterm.upload.password"]
            }
        }
    }

    buildTypes {
        // Its own package, so it installs beside the Google Play build on the same phone.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Shrunk and obfuscated; the libraries' own rules suffice (`play-release` D3).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadMissing.isEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
        // A release build signed with the debug key, installable over a debug build (same key, data kept), to
        // judge smoothness on the phone: a debug build is debuggable, which keeps ART from compiling it ahead of
        // time, so it stutters until the JIT has warmed up.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Lists English and zh-TW in Android 13+'s per-app language settings (`scan-connect-zh` D8).
    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.cpp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    // Draws QR codes, and runs the old decoding path, for QrDecodeTest (`scan-connect-zh` D1, D2).
    androidTestImplementation(libs.zxing.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// A release build without the upload key would be written unsigned; fail at once instead, before R8 (`play-release` D1).
val checkUploadKey = tasks.register("checkUploadKey") {
    val missing = uploadMissing
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The upload key is not configured. Missing Gradle properties: ${missing.joinToString()}. " +
                    "Set them in ~/.gradle/gradle.properties (see docs/play-release.md).",
            )
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(checkUploadKey)
}

/**
 * Every library the release ships has an entry in the Licenses screen's file, and none is an analytics, advertising or
 * crash-reporting SDK (`play-release` D4, `play-distribution`). Resolves the release runtime classpath, all of it.
 */
abstract class CheckLicenses : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val licenses: RegularFileProperty

    @TaskAction
    fun check() {
        val lines = licenses.get().asFile.readLines()
        val start = lines.indexOf("[libraries]")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("[") } ?: lines.size
        val patterns = lines.subList(start + 1, end).filter { " — " in it }.map { it.substringBefore(" — ").trim() }
        fun covered(coordinate: String) = patterns.any { p ->
            val group = coordinate.substringBefore(':')
            when {
                ':' in p -> coordinate == p
                p.endsWith(".*") -> group == p.removeSuffix(".*") || group.startsWith(p.removeSuffix("*"))
                else -> group == p
            }
        }
        val vendors = coordinates.get().filter { c -> VENDORS.any { c.startsWith(it) } }
        val missing = coordinates.get().filter { !covered(it) }
        val problems = buildList {
            if (vendors.isNotEmpty()) add("Analytics, advertising or crash-reporting libraries:\n  " + vendors.joinToString("\n  "))
            if (missing.isNotEmpty()) add("No entry in ${licenses.get().asFile.name}:\n  " + missing.joinToString("\n  "))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }

    companion object {
        /** The vendors the app must never ship (`play-distribution`). */
        val VENDORS = listOf(
            "com.google.firebase:", "com.google.android.gms:play-services-ads", "com.google.android.gms:play-services-analytics",
            "com.google.android.gms:play-services-measurement", "com.crashlytics", "io.sentry:", "com.bugsnag:", "com.appsflyer:",
            "com.adjust.sdk:", "com.mixpanel.android:", "com.amplitude:",
        )
    }
}

val checkLicenses = tasks.register<CheckLicenses>("checkLicenses") {
    coordinates.set(
        configurations.named("releaseRuntimeClasspath").flatMap { classpath ->
            classpath.incoming.resolutionResult.rootComponent.map { root ->
                val seen = sortedSetOf<String>()
                fun walk(component: ResolvedComponentResult) {
                    for (dependency in component.dependencies) {
                        if (dependency !is ResolvedDependencyResult) continue
                        val id = dependency.selected.id
                        if (id is ModuleComponentIdentifier && seen.add("${id.group}:${id.module}")) walk(dependency.selected)
                    }
                }
                walk(root)
                seen.toList()
            }
        },
    )
    licenses.set(layout.projectDirectory.file("src/main/res/raw/third_party_licenses.txt"))
}
tasks.named("check") { dependsOn(checkLicenses) }
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(checkLicenses)
}

// Tests that read files — the listing's limits, the strings, the sources' literals and comments — run again when those
// files change, not only when the compiled classes do (`play-release` D6).
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("play/listings")).withPropertyName("playListings").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/res")).withPropertyName("mainResources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/java")).withPropertyName("mainSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
