plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.splashskip.a11y"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":rules"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.coroutines.android)
    // HitLogStore 用 Gson 序列化 HitRecord（JSONL），这一行之前漏了
    implementation(libs.gson)
    // Shizuku：免无障碍的点击通道
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
