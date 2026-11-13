plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.updater"
    compileSdk = 37
    defaultConfig { minSdk = 23; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.nio)
    implementation(libs.android.core)
    implementation(libs.coroutines.core)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
