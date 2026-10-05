plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.sidebar"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":localization"))
    implementation(project(":designsystem"))
    implementation(libs.coroutines.android)
}
