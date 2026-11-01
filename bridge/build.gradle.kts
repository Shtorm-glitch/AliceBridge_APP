plugins { id("com.android.library") }
android {
    namespace = "dev.alicebridge.bridge"
    compileSdk = 35
    defaultConfig { minSdk = 32 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    compileOnly(files("libs/hev-classes.jar"))
}
