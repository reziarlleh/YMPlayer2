plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.sidebar"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.android.core)
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(project(":localization"))
    implementation(project(":designsystem"))
    implementation(libs.coroutines.android)
}
