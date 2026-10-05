plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.petrov.ymplayer2.yandex"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
}
