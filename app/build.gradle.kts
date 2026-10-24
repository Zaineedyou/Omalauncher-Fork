import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from the gitignored keystore.properties at the repo root.
val uploadKey = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// The NDK proot is built with (scripts/fetch-proot.sh); wlbridge uses the same one.
val prootNdk = rootProject.file("scripts/fetch-proot.sh").readLines().first { it.startsWith("NDK_VERSION=") }.substringAfter('=')

android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    ndkVersion = prootNdk

    defaultConfig {
        applicationId = "dev.omalauncher"
        minSdk = 29
        targetSdk = 37
        versionCode = 19
        versionName = "0.10.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir: proot is exec'd from there.
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so")
        }
    }

    // android.util.Log and friends are no-ops in JVM tests instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        create("release") {
            uploadKey?.let {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks the APK and drops what the app doesn't use, like the owl module's unused parts.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "release" else "debug")
        }
    }
}

// ExamplesTest reads the widget examples and the README from here, so editing them reruns it.
tasks.withType<Test>().configureEach { inputs.dir("src/main/assets/widgets") }

// Gitignored, built by scripts/fetch-proot.sh. Without it a release still builds, but every Linux command fails.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val proot = file("src/main/jniLibs/arm64-v8a/libproot.so")
    doFirst { check(proot.exists()) { "$proot is missing; run scripts/fetch-proot.sh" } }
}

// A release signed with the debug key installs, but it's still a debug-signed app. Without keystore.properties
// (see BUILD.md) stop instead of quietly doing that; -PallowDebugSigning=true asks for it.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val hasKey = uploadKey != null
    val allowed = providers.gradleProperty("allowDebugSigning").orNull == "true"
    doFirst {
        check(hasKey || allowed) {
            "keystore.properties is missing, so the release would be signed with the debug key. " +
                "Run scripts/make-keystore.sh, or pass -PallowDebugSigning=true to sign with the debug key anyway."
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.commons.compress)
    implementation(libs.androidsvg)
    implementation(libs.zxing.core)
    implementation(project(":owl"))
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json.
    testImplementation(libs.org.json)
}
