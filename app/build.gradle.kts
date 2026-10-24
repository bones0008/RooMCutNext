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
        versionCode = 46
        versionName = "1.6.0"
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
            // v1.6.0: R8 ON — 此前体积 56MB 的 89% 是未裁剪的 dex（Compose +
            // material-icons-extended + Media3 整包）。keep 策略：业务包
            // （@Serializable 项目模型 + 手写序列化边界）整体保留，裁剪只
            // 发生在库代码 —— 体积收益全部来自库侧，反射风险≈0。
            isMinifyEnabled = true
            isShrinkResources = true
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
    // v1.6.0: AI agent 的 LLM HTTP 客户端（Coil 已传递引入，显式声明使用面）。
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
