import java.util.Properties

plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
val versionInfo = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val issuedBuild = providers.gradleProperty("issuedBuildNumber").orNull?.toInt()
val base = versionInfo.getProperty("baseVersion")
val channel = versionInfo.getProperty("channel")
val yandexInfo = Properties().apply {
    rootProject.file(".provider/yandex.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun yandexValue(name: String): String {
    val value = yandexInfo.getProperty(name, "")
    require(value.matches(Regex("[A-Za-z0-9_.-]*"))) { "Invalid OAuth client configuration format." }
    return "\"$value\""
}
val signingInfo = Properties().apply {
    rootProject.file(".signing/signing.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
android {
    namespace = "dev.petrov.ymplayer2"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.petrov.ymplayer2"
        minSdk = 29
        targetSdk = 36
        versionCode = issuedBuild ?: 1
        versionName = if (issuedBuild == null) "$base-internal" else "$base${if (channel == "beta") "beta" else ""}-build$issuedBuild"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YANDEX_CLIENT_ID", yandexValue("clientId"))
        buildConfigField("String", "YANDEX_CLIENT_SECRET", yandexValue("clientSecret"))
    }
    signingConfigs {
        if (signingInfo.isNotEmpty()) create("product") {
            storeFile = rootProject.file(".signing/ymplayer2.jks")
            storePassword = signingInfo.getProperty("storePassword")
            keyAlias = "ymplayer2"
            keyPassword = signingInfo.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        create("minifiedDebug") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev.minified"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += listOf("release")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("product")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":app:assembleRelease" || it.path == ":app:bundleRelease" }) {
        require(issuedBuild != null && issuedBuild > 0) { "Issue a build using tools/Build-Release.ps1." }
        require(signingInfo.isNotEmpty()) { "Configure the independent 2.x signing key first." }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":library:local"))
    implementation(project(":library:offline"))
    implementation(project(":playback:android"))
    implementation(project(":provider:yandex"))
    implementation(libs.coroutines.android)
    implementation(project(":designsystem"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:clips"))
    implementation(libs.media3.exoplayer)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.media3.exoplayer)
    androidTestImplementation(libs.media3.session)
    debugImplementation(libs.compose.test.manifest)
}
