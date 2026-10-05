plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.playback"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":localization"));
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}
