plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.totomika.pocketmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.totomika.pocketmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
    // i18n: 应用通过 res/values (en 默认) + res/values-zh-rCN (zh) 提供本地化资源,
    // 并经 AndroidManifest 的 android:localeConfig="@xml/locales_config" 声明支持的语言。
    // 注意: AGP 9.x Kotlin DSL 中 resConfigs/resourceConfigurations 访问方式有变,
    // 暂不配置 locale 过滤 (仅影响 APK 体积, 不影响 i18n 功能)。
    // Compose compiler plugin (org.jetbrains.kotlin.plugin.compose) manages the compiler,
    // so composeOptions { kotlinCompilerExtensionVersion = ... } is no longer needed.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // MCP 协议 (官方 Kotlin SDK, Streamable HTTP transport)
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")

    // HTTP/SSE 引擎
    implementation("io.ktor:ktor-server-cio:3.5.0")
    implementation("io.ktor:ktor-server-sse:3.5.0")

    // QuickJS 运行时 (原版 QuickJS by bellard, 非 QuickJS-NG)
    implementation("io.github.dokar3:quickjs-kt-android:1.0.5")

    // Android 基础
    // 注: 文档原写 core:1.19.0, 但该版本要求 AGP 9.1+ 和 compileSdk 37, 与文档的 AGP 8.7+compileSdk 36 冲突。
    // 降到 1.18.0 保持兼容。1.19.0 起 ktx 工件已合并进 core, 1.18.0 仍需 core-ktx。
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Compose UI (BOM 统一管理版本)
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // 持久化
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // QR code 生成 (纯 Java, 体积小)
    implementation("com.google.zxing:core:3.5.3")

    // 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}