plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.localization"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.coroutines.core)
}
