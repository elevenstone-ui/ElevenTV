import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin，不需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 刮削凭证不进源码：写在 local.properties（已被 .gitignore 排除），
// 构建期注入 BuildConfig，运行时按 BuildConfig 默认值预填设置页。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tmdbApiKey: String = localProps.getProperty("tmdb.apiKey").orEmpty()
val tmdbReadToken: String = localProps.getProperty("tmdb.readToken").orEmpty()
val fanartApiKey: String = localProps.getProperty("fanart.apiKey").orEmpty()

// 构建时间戳写进 BuildConfig：两个功能差别很大的包若共用同一个 versionName，
// 电视上根本分不清装的是哪个（2.0.2 / versionCode 3 曾经同时对应 SMB 版和 WebDAV 版）。
// 关于页显示它，用户报问题时一句话就能定位到具体产物。
// 注意：不能写 java.text.SimpleDateFormat——Kotlin DSL 里的 `java` 会被解析成
// JavaPluginExtension，必须走顶部 import，否则报 Unresolved reference 'text'。
val buildStamp: String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())

android {
    namespace = "com.eleventv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eleventv.tv"
        // A9F 出厂 Android 8.0（API 26）；26 起自适应图标可用
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.1-webdav"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_READ_TOKEN", "\"$tmdbReadToken\"")
        buildConfigField("String", "FANART_API_KEY", "\"$fanartApiKey\"")
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
    }

    buildTypes {
        release {
            // 开混淆 + 资源压缩：APK 里能看到类名就等于把内部结构（尤其是刮削流程与
            // 凭证读取路径）直接摊开给人看，配合「源码里不再内置 Key」才谈得上安全。
            // 规则见 proguard-rules.pro（serialization 的 serializer、smbj 的反射）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)

    implementation(libs.smbj)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    // 解析/归并是整库的底座：命名规则一改就可能把一部剧拆成几十条，
    // 而这些分支没法在电视上手动验，只能靠单元测试兜住。
    testImplementation(libs.junit)
}
