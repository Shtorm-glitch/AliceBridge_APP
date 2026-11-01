import java.io.File
plugins { id("com.android.library") }
android {
    namespace = "dev.alicebridge.station"
    compileSdk = 35
    defaultConfig { minSdk = 32 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(project(":bridge"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
val wifiClasses = layout.buildDirectory.dir("stationWifi/classes")
val wifiAssets = layout.buildDirectory.dir("generated/stationWifiAssets")
val compileWifi = tasks.register<JavaCompile>("compileStationWifi") {
    source(fileTree("src/station1Wifi/java") { include("**/*.java") })
    classpath = files()
    sourceCompatibility = "1.7"
    targetCompatibility = "1.7"
    destinationDirectory.set(wifiClasses)
}
val dexWifi = tasks.register<Exec>("dexStationWifi") {
    dependsOn(compileWifi)
    inputs.dir(wifiClasses)
    outputs.file(wifiAssets.map { it.file("station1/wifi-helper.jar") })
    doFirst {
        val output = wifiAssets.get().file("station1/wifi-helper.jar").asFile
        output.parentFile.mkdirs()
        commandLine(
            listOf(
                File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/d8").absolutePath,
                "--min-api",
                "23",
                "--output",
                output.absolutePath,
            ) +
                fileTree(wifiClasses.get().asFile).matching { include("**/*.class") }.files.map { it.absolutePath },
        )
    }
}
android.sourceSets.named("main") { assets.srcDir(wifiAssets) }
tasks.named("preBuild") { dependsOn(dexWifi) }
