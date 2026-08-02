plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The version ships from one place: APP_VERSION in app.py, which CI passes in as
 * `-PclarifiVersion=X.Y.Z`. The literal below is only the fallback for local
 * builds, and must be kept in step with it (see CLAUDE.md rule 14).
 */
val clarifiVersion = (findProperty("clarifiVersion") as String?) ?: "0.3.3"

/**
 * `1.2.3` → `1002003`, so Play and side-loads both order releases correctly.
 *
 * Three digits per component, not two: Play rejects a version code it has already
 * seen, and a tighter packing makes `0.2.100` collide with `0.3.0`. There is no
 * cost to the headroom.
 */
fun versionCodeFrom(name: String): Int {
    val parts = name.split('.').mapNotNull { it.toIntOrNull() }
    val (major, minor, patch) = List(3) { parts.getOrElse(it) { 0 } }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.clarifi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clarifi"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeFrom(clarifiVersion)
        versionName = clarifiVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing details come from the environment so the keystore and its passwords
    // never enter the repository. Without them the release APK builds unsigned,
    // which is fine locally and fails loudly in CI instead of shipping silently.
    val keystorePath = System.getenv("CLARIFI_KEYSTORE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CLARIFI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLARIFI_KEY_ALIAS")
                keyPassword = System.getenv("CLARIFI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Room's exported schemas are committed so future migrations can be diffed
    // and tested against the shipped version.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // The AI key never leaves the device, so it is kept behind the Android Keystore
    // rather than the light obfuscation the desktop uses.
    implementation(libs.androidx.security.crypto)

    // Receipt capture. HTTP and JSON go through the platform's own HttpURLConnection
    // and org.json - no third-party client is worth the weight for four endpoints.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Supabase sync speaks the Postgres wire protocol so the phone takes the same
    // connection string as the desktop. pgjdbc cannot: it calls
    // java.lang.management.ManagementFactory on every connection and that class does
    // not exist on Android (see CLAUDE.md). jasync implements the protocol itself.
    implementation(libs.jasync.postgresql)

    // Home-screen widget and the daily due-payment check.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar's org.json is a stub that throws; JVM tests need the real thing.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
