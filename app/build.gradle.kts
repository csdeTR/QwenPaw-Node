import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * release 签名配置。
 *
 * 密钥与口令都放在仓库外的 keystore.properties（默认 ~/.qwenpaw-node-keys/），
 * 可用环境变量 QWENPAW_KEYSTORE_PROPERTIES 指向别处。文件不存在时 release
 * 退化为未签名（仍可构建，只是不能安装/发布），保证 clone 下来能直接编。
 */
val keystorePropsFile: File = System.getenv("QWENPAW_KEYSTORE_PROPERTIES")
    ?.let { File(it) }
    ?: File(System.getProperty("user.home"), ".qwenpaw-node-keys/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "io.qwenpaw.node"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.qwenpaw.node"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 「关于」页里展示的仓库地址，单独抽成 BuildConfig 字段，
        // fork 之后改这一处即可，不用去翻界面代码。
        buildConfigField(
            "String",
            "REPO_URL",
            "\"https://github.com/csdeTR/QwenPaw-Node\"",
        )
        buildConfigField(
            "String",
            "BRIDGE_REPO_URL",
            "\"https://github.com/csdeTR/QwenPaw-Node-Bridge\"",
        )

        // 按 ABI 拆分：四个架构各出一个包，每台设备只下载自己架构的那份。
        // 与 abiFilters 二选一，同时写时 abiFilters 会被忽略。
        // full 变体每个架构带一份 sherpa-onnx 的 .so（14–30MB），合进一个包会成倍放大；
        // nosherpa 的包只有 2.4MB 左右，同样拆开以免旧设备下载无关架构。
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                isUniversalApk = false
            }
        }

        // 两个变体：full 带端上识别引擎，nosherpa 不带。
        // 0.4.0 一度只留服务端转写，实测体验不如本地识别，因此恢复为双变体：
        // full 可在本地识别与服务端转写之间二选一（默认本地），
        // nosherpa 只能走服务端，体积小约 22MB。
        // 变体名只体现在产物文件名上，applicationId 相同，可互相覆盖升级。
        flavorDimensions += "asr"
        productFlavors {
            create("full") {
                dimension = "asr"
                versionNameSuffix = ""
            }
            create("nosherpa") {
                dimension = "asr"
                // 后缀进 APK 文件名（QwenPaw-Node-<tag>-no-sherpa-<abi>.apk），
                // 不进 applicationId：两个变体共用一套登录态与缓存。
                versionNameSuffix = "-no-sherpa"
            }
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug 与 release 用不同包名，两个包可以同时装在一台设备上。
            // 包名相同的话每次装 debug 都要先卸载 release，登录状态与缓存一起丢掉。
            // 应用名在 src/debug/res/values/strings.xml 里覆盖为「[debug]QP Node」。
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // 工作区目录下载需要在 SAF 授权目录里逐级建子目录与文件
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // 用量统计的趋势折线。选 2.0.0 而不是更新的版本：它的 compose-bom 为 2024.12.01，
    // 与本项目完全一致；2.1 之后会把 Compose 一起抬高，3.x 已转 KMP、要求 Kotlin 2.4。
    implementation("com.patrykandpatrick.vico:compose:2.0.0")
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // 头像裁剪需要按 EXIF 方向旋转，否则竖拍照片会躺倒
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 头像可能是 GIF。coil-gif 提供逐帧播放的解码器，静态位图只能显示首帧。
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 端上识别引擎只在 full 变体里。AAR 不在 Maven 中央仓库，放在 app/libs。
    // nosherpa 变体不带这个依赖，因此四个 ABI 的包里都不会出现它的 .so。
    "fullImplementation"(files("libs/sherpa-onnx-1.13.3.aar"))
    // 模型是 tar.bz2，解压需要 bzip2。只有 full 变体会下载模型。
    "fullImplementation"("org.apache.commons:commons-compress:1.26.2")
    // Vico core 声明 kotlinx-coroutines-core 1.10.1，这里与它对齐，避免
    // 「core 1.10.1 + android 1.9.0」这种半升级状态带来的运行期 NoSuchMethodError。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
