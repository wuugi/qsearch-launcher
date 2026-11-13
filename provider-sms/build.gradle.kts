plugins {
    alias(libs.plugins.android.library)
}

// Phase 8 (SMS-04) — SMS 권한과 시스템 SMS provider 조회가 사는 유일한 모듈.
// 의존성 목록이 곧 감사 대상이다: 코루틴 외 런타임 라이브러리가 없고, 특히 네트워크
// 라이브러리가 없다 — 이 모듈은 구조적으로 데이터를 기기 밖으로 보낼 수 없다.
// `./gradlew checkSmsIsolation`(app/build.gradle.kts)이 이 파일과 매니페스트를 검사한다.
android {
    namespace = "io.github.wuugi.qsearch.sms"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
