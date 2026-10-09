plugins { id("com.android.application") version "9.3.2" }
android {
    namespace = "dev.petrov.monitorfixture"
    compileSdk = 37
    defaultConfig {
        applicationId = providers.gradleProperty("fixturePackage").getOrElse("dev.petrov.monitorfixture")
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "TEST-FIXTURE-NOT-OFFICIAL"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
