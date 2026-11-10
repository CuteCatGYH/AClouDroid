plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.acloudroid.qwq"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.acloudroid.qwq"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式版在桌面上显示的名字
            resValue("string", "app_name", "AClouDroid")
            // 注意：不要在这里加 optimization { } 块。
            // isMinifyEnabled = false 只关闭 shrink/obfuscate，并不会关闭 R8 的
            // class-merging/repackaging；两者叠加会把 kotlin.** / androidx.** 重打包成
            // 单字母包，导致 Room/WorkManager 初始化时抛 IllegalAccessError
            // （"Class kotlin.text.j extended by class sh2 is inaccessible"），
            // 表现为 release 版一启动就闪退。要真正启用优化，必须同时打开
            // isMinifyEnabled = true 并补齐 keep 规则。
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 测试版在桌面上显示的名字（自动带上后缀，一眼区分）
            resValue("string", "app_name", "AClouDroid (Debug)")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // P2-013: auth diagnostics are gated behind BuildConfig.DEBUG so the verbose cookie-probe
        // lines are compiled out of release builds entirely.
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(files("libs/icloudcore.aar"))

    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging 3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // WorkManager: keeps the month scan running while the app is backgrounded (P2-016).
    implementation(libs.androidx.work.runtime)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Shizuku: enables shell-level execution to register and set system cloud media providers
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Extended Icons
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Real org.json implementation for JVM unit tests. The android.jar stub throws on every
    // call in the local test runtime, so JSON-parsing logic (classifyCoreResponse) can only be
    // exercised against this.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
