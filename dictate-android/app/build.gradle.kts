import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx ships its Kotlin API + JNI libs as one AAR on GitHub releases (not on Maven).
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

val fetchSherpa by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

android {
    namespace = "run.bestmodel.dictate"
    compileSdk = 35

    defaultConfig {
        applicationId = "run.bestmodel.dictate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug key so the APK installs by sideloading; swap for a real key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Compressed native libs: ~half the APK size; extracted once at install.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(files(sherpaAar))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}

// Optional end-to-end engine test on the JVM with the real model and the Linux JNI build of
// sherpa-onnx: ./gradlew :app:testDebugUnitTest -Pdictate.modelDir=… -Pdictate.audio=… -Pdictate.jni=…
tasks.withType<Test>().configureEach {
    listOf("modelDir", "audio", "jni").forEach { key ->
        project.findProperty("dictate.$key")?.let { systemProperty("dictate.$key", it) }
    }
    project.findProperty("dictate.jni")?.let {
        systemProperty("java.library.path", it)
        environment("LD_LIBRARY_PATH", it)
    }
}
