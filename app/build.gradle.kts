plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.todocalendar"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.todocalendar"
        minSdk = 26
        targetSdk = 36
        // 릴리스 워크플로가 태그/실행 번호로 지정 (없으면 기본값)
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        // AdMob: 기본값은 구글 공식 테스트 ID. 실제 광고는 환경변수 ADMOB_APP_ID / ADMOB_BANNER_ID 로 지정한다
        manifestPlaceholders["admobAppId"] = System.getenv("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "ADMOB_BANNER_ID", "\"${System.getenv("ADMOB_BANNER_ID") ?: "ca-app-pub-3940256099942544/6300978111"}\"")
    }
    // 저장소에 포함된 고정 키로 서명: 어느 빌드(CI 포함)든 서명이 같아서, 앱을 지우지 않고 덮어 설치해도 데이터가 유지된다.
    // 스토어 배포용 서명은 환경변수(RELEASE_KEYSTORE 등)로 따로 지정한다.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        System.getenv("RELEASE_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    // 광고 SDK가 끌고 오는 구버전 fragment 때문에 릴리스 린트(registerForActivityResult)가 실패하므로 올려서 고정
    implementation("androidx.fragment:fragment:1.8.5")
}
