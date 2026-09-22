plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.co.rateplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.rateplanner"
        minSdk = 24          // 안드로이드 7.0 이상
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 서버 주소 (배포용 고정)
        buildConfigField("String", "BASE_URL", "\"https://ponsale.co.kr/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true; viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
}
