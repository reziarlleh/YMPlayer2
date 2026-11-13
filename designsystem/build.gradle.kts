plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose.compiler) }
android {
    namespace = "dev.petrov.ymplayer2.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    buildFeatures { compose = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(project(":localization"));
    implementation(libs.android.core)
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.foundation)
    implementation(libs.coroutines.android)
}
