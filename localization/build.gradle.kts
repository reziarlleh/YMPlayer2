plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.localization"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.coroutines.core)
}
