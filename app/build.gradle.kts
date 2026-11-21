plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.aqi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aqi"
        minSdk = 26          // Android 8.0：通知渠道、自适应图标、java.time 均可用
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // v0.4.0 设置页要显示版本号：AGP 8 起 BuildConfig 默认关闭，必须显式打开
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 只为「真实画一遍 Canvas 导出 PNG」这一个目的引入 —— 沙箱里没有模拟器，
    // 这是唯一能验证 GaugePainter 像素是否正确的方法。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
