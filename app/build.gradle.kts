import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is configured out of band: a gitignored keystore.properties
// at the repo root, or RELEASE_* environment variables (for CI). With neither
// present, release builds stay unsigned rather than failing, so a fresh clone
// still builds.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStoreFile: String? = signingValue("storeFile", "RELEASE_STORE_FILE")

android {
    namespace = "com.workouttracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.workouttracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // CI signs debug builds with the owner's own debug key when it has one
        // (see "Installing from your phone" in the README), so the APK it
        // publishes installs as an update over a build from Android Studio:
        // same key, same app, same data, and Drive sign-in still matches the
        // SHA-1 registered for it. Without one, the runner makes a throwaway
        // key per run and nothing it builds can update an installed app.
        // Alias and passwords stay the debug defaults, which is what Android
        // Studio generated the key with.
        getByName("debug") {
            System.getenv("DEBUG_KEYSTORE_FILE")?.let { storeFile = file(it) }
        }
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left unsigned when no keystore is configured; assembleRelease then
            // produces app-release-unsigned.apk, which is what CI builds.
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true

        // These tests only ever run on CI, where the HTML report is an
        // artifact nobody reads. Print the failure and its cause to the
        // console instead, so a stack trace is in the build log.
        unitTests.all { test ->
            // Robolectric sets up an SDK 36 environment, whose
            // ApplicationSharedMemory.create sends its FileDescriptorInterceptor
            // into jdk.internal.access.SharedSecrets. The module system refuses
            // that by default and every Robolectric test then dies in setup,
            // before reaching a single assertion.
            test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")

            test.testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    // Compose has no reorderable list of its own, and hand-rolled drag
    // maths is a poor thing to write without a device to try it on.
    implementation(libs.reorderable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
