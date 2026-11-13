import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val signingFile = rootProject.file("signing.properties")
val releaseSigning = if (signingFile.exists()) {
    Properties().apply {
        signingFile.inputStream().use { load(it) }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach {
            require(!getProperty(it).isNullOrBlank()) { "signing.properties: missing $it" }
        }
        require(rootProject.file(getProperty("storeFile")).isFile) { "signing.properties: keystore not found" }
    }
} else null

android {
    if (releaseSigning != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    namespace = "com.rootlyt.aio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rootlyt.aio"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Android supplies org.json on device; JVM parser regression needs its real implementation, not Android stubs.
    testImplementation("org.json:json:20160810")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
// Framework-free JVM checks for the actual URL/filename rules used by the app.
tasks.register<JavaExec>("checkRules") {
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files("$buildDir/tmp/kotlin-classes/debug", "$buildDir/tmp/kotlin-classes/debugUnitTest") + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("com.rootlyt.aio.RulesCheckKt")
}
