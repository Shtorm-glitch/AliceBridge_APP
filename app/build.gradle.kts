plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.alicebridge.app"
    compileSdk = 35
    defaultConfig {
        // Same identity preserves KernelSU grants and the reviewed native bootstrap paths.
        applicationId = "ab.root"
        minSdk = 32
        targetSdk = 35
        versionCode = 1000021
        versionName = "1.0.21"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Keep the existing distributed app certificate so Android accepts the update.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(project(":domain"))
    implementation(project(":bridge"))
    implementation(project(":station"))
    implementation(files("../bridge/libs/hev-socks5-tunnel-2.19.3.aar"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
