import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.RooMCutNext"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.RooMCutNext"
        minSdk = 31
        targetSdk = 35
        versionCode = 41
        versionName = "1.5.5"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release signing is read from local.properties (git-ignored) or env
        // vars so key material never enters the repository:
        //   roocmcut.keystore=/absolute/path/to/release.keystore
        //   roocmcut.storePassword=...
        //   roocmcut.keyPassword=...   (defaults to store password)
        //   roocmcut.keyAlias=...      (defaults to roocmcutnext)
        // or ROOCMCUT_KEYSTORE / ROOCMCUT_STORE_PASSWORD env vars.
        // When unconfigured, builds fall back to the debug keystore so a
        // fresh clone builds out of the box.
        create("release") {
            val props = Properties()
            runCatching {
                rootProject.file("local.properties").inputStream().use { props.load(it) }
            }
            val ksPath = props.getProperty("roocmcut.keystore")
                ?: System.getenv("ROOCMCUT_KEYSTORE")
            val ksPass = props.getProperty("roocmcut.storePassword")
                ?: System.getenv("ROOCMCUT_STORE_PASSWORD")
            if (!ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty() && rootProject.file(ksPath).exists()) {
                storeFile = rootProject.file(ksPath)
                storePassword = ksPass
                keyAlias = props.getProperty("roocmcut.keyAlias") ?: "roocmcutnext"
                keyPassword = props.getProperty("roocmcut.keyPassword") ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            // V1 keeps minify off for maximum stability; enable later with proper keep rules.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // Media3 APIs marked @UnstableApi are opted-in explicitly where used.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
