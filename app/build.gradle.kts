plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}
android {
    namespace = "dev.calendar.mobile"
    compileSdk = 36
    sourceSets["main"].assets.srcDir("../assets/icons")
    defaultConfig {
        applicationId = "dev.calendar.mobile.android"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("calendarVersionCode").map(String::toInt).getOrElse(1)
        versionName = "1.0"
        val googleClient = providers.gradleProperty("calendarGoogleWebClientId")
            .getOrElse("153961912406-prh532ifqkg649tes40r78ireubadv3r.apps.googleusercontent.com")
        require(googleClient.matches(Regex("[a-z0-9-]+[.]apps[.]googleusercontent[.]com")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClient\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
