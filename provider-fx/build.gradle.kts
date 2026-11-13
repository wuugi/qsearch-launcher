plugins {
    alias(libs.plugins.android.library)
}

// Phase 11 (FX-07) — 저장소에서 INTERNET 권한을 선언하는 유일한 모듈이다(환율 조회 하나).
// 의존성 목록이 곧 감사 대상이다: 코루틴 외 런타임 라이브러리가 없고, 특히 네트워크 라이브러리가
// 없다 — 플랫폼 HttpsURLConnection을 FxClient.kt 한 파일(11-03)에서만 쓴다. 이 모듈은 :provider-sms에
// 의존하지 않는다(규칙 F). `./gradlew checkSmsIsolation`(app/build.gradle.kts)이 이 파일과 매니페스트를 검사한다.
android {
    namespace = "io.github.wuugi.qsearch.fx"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // 플랫폼 org.json은 JVM 단위 테스트에서 Stub!을 던진다 — FxRatesTest가 parse를 JVM에서 돌리려고 표준 org.json을
    // 테스트 전용으로 둔다. APK에 들어가지 않는다(런타임은 플랫폼 org.json). implementation으로 바꾸지 않는다.
    // 버전은 Maven Central 메타데이터(latest/release)에서 2026-10-31 확인.
    testImplementation(libs.org.json)
}
