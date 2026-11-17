plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitHash: String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().readText().trim()
}.getOrDefault("unknown").ifBlank { "unknown" }

android {
    namespace = "dev.g5.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.g5.companion"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "v0.2.0"
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ponytail: release signs with the debug key so it installs out of the box; set a real signingConfig before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.md") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.google.genai)
    implementation(libs.anthropic.java)
    implementation(libs.litertlm.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json; Android's is a stub in JVM tests
}
