import java.util.Properties

plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
val versionInfo = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val issuedBuild = providers.gradleProperty("issuedBuildNumber").orNull?.toInt()
val base = versionInfo.getProperty("baseVersion")
val channel = versionInfo.getProperty("channel")
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
    implementation(project(":playback:android"))
    implementation(libs.coroutines.android)
    implementation(project(":designsystem"))
    implementation(project(":feature:shell"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    debugImplementation(libs.compose.test.manifest)
}
