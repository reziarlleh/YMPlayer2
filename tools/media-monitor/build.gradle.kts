import java.util.Properties

plugins { id("com.android.application") version "9.3.2" }
val signingFile = file("../../.signing/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "dev.petrov.mediamonitor"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.petrov.mediamonitor"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0beta-build1"
    }
    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = file("../../.signing/ymplayer2.jks")
            storePassword = signing.getProperty("storePassword")
            keyAlias = "ymplayer2"
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.findByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
