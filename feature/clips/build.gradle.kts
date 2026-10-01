plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.clips"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":designsystem"))
    implementation(project(":core"))
    implementation(project(":provider:yandex"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.coroutines.android)
}
