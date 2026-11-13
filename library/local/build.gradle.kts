plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.library"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.android.core)
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(project(":core"))
    implementation(libs.coroutines.android)
}
