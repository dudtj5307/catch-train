// 루트 빌드 스크립트. 플러그인은 하위 모듈에서 적용한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
