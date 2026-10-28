// 纯 JVM 逻辑模块：规则模型 / 匹配器 / 调度引擎。
// 刻意不依赖任何 android.* 类，便于在 JVM 上跑单元测试（无需 Robolectric）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.splashskip.core"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
