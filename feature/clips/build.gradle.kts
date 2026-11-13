plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.clips"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(project(":localization"));
    implementation(project(":designsystem"))
    implementation(project(":core"))
    implementation(project(":provider:yandex"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.coroutines.android)
}
