plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose.compiler) }
android {
    namespace = "dev.petrov.ymplayer2.shell"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":localization"));
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.core)
}
