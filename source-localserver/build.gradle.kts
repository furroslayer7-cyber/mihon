plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
    alias(libs.plugins.metro)
}

android {
    namespace = "eu.kanade.tachiyomi.source.localserver"
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.i18n)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(libs.metro.runtime)
    implementation(libs.jsoup)
    implementation(libs.bundles.okhttp)
    implementation(libs.bundles.kotlinx.coroutines)
}
