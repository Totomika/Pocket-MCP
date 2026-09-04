import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ===== CI 注入的可选配置 (GitHub Actions 发布时通过环境变量传入, 本地构建留空不受影响) =====
// 发布签名: 正式 keystore 以 base64 形式存于仓库 Secrets, CI 解码后临时写入文件用于签名
// 注1: 变量名避开 SigningConfig 的同名属性 (keyAlias/keyPassword), 防止 DSL 块内 receiver 遮蔽
// 注2: keystore 在配置阶段即写入文件 (配置期副作用), 若日后启用 Gradle configuration cache 需改造
val ciKeystoreBase64: String? = System.getenv("KEYSTORE_BASE64")
val ciKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val ciKeyAlias: String? = System.getenv("KEY_ALIAS")
val ciKeyPassword: String? = System.getenv("KEY_PASSWORD")
// 版本号覆盖: 优先 -P 命令行属性 (./gradlew -PversionName=26.08.6-rc2), 其次环境变量 (发布流水线注入)
// 本地/PR 构建固定 versionName="dev"、versionCode=1: 不参与发布版本规则, 规则见 docs/release.md
val ciVersionName: String? = providers.gradleProperty("versionName").orNull
    ?: System.getenv("VERSION_NAME")
val ciVersionCode: Int? = (providers.gradleProperty("versionCode").orNull
    ?: System.getenv("VERSION_CODE"))?.toIntOrNull()

android {
    namespace = "io.github.totomika.pocketmcp"
    compileSdk = 36

    signingConfigs {
        if (!ciKeystoreBase64.isNullOrEmpty() && !ciKeystorePassword.isNullOrEmpty()
            && !ciKeyAlias.isNullOrEmpty() && !ciKeyPassword.isNullOrEmpty()
        ) {
            create("ciRelease") {
                // CI 上写入临时目录; 本地调试时写入 build 目录 (均不会被 git 跟踪)
                val keystoreFile = File(
                    System.getenv("RUNNER_TEMP") ?: layout.buildDirectory.get().asFile.absolutePath,
                    "release-keystore.jks"
                )
                // MimeDecoder 容忍换行/空白, 避免 Secrets 中 base64 折行导致解码失败
                keystoreFile.writeBytes(Base64.getMimeDecoder().decode(ciKeystoreBase64))
                this.storeFile = keystoreFile
                this.storePassword = ciKeystorePassword
                this.keyAlias = ciKeyAlias
                this.keyPassword = ciKeyPassword
            }
        } else {
            // Secrets 不完整时显式告警; CI 上 workflow 另有前置校验会快速失败, 此处兜底本地调试场景
            logger.warn("ciRelease signing skipped: KEYSTORE_* env vars incomplete")
        }
    }

    defaultConfig {
        applicationId = "io.github.totomika.pocketmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "dev"

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
            // 仅当 CI 注入了签名配置时才签名, 本地无 env 时保持原行为 (不签名)
            signingConfigs.findByName("ciRelease")?.let { signingConfig = it }
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