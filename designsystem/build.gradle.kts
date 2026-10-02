plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose.compiler) }
android {
    namespace = "dev.petrov.ymplayer2.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":localization"));
    implementation(libs.android.core)
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.foundation)
    implementation(libs.coroutines.android)
}
