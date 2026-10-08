plugins { `java-library` }
repositories { google(); mavenCentral() }
dependencies {
    implementation("com.android.tools.build:gradle-api:9.3.2") {
        isTransitive = false
    }
    implementation("com.android.tools.build:gradle-common-api:9.3.2") { isTransitive = false }
    implementation("com.android.tools.build:builder-test-api:9.3.2") { isTransitive = false }
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("org.ow2.asm:asm:9.9")
    testImplementation("junit:junit:4.13.2")
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
