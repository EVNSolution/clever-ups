import dev.detekt.gradle.Detekt

plugins {
    id("com.android.application")
    id("dev.detekt")
}

android {
    namespace = "com.evnsolution.ups"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evnsolution.ups"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        // The emulator reaches the host machine through 10.0.2.2. A phone on USB can use `adb reverse` and
        // -PupsApiBase=http://127.0.0.1:8080.
        val apiBase = providers.gradleProperty("upsApiBase").getOrElse("http://10.0.2.2:8080")
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// The contract test reads this file, so Gradle must rerun tests when it changes.
tasks.withType<Test>().configureEach {
    inputs.file("../../../contract/openapi.yaml")
}

dependencies {
    // FileProvider: the camera writes the door photo into the app's cache without touching the gallery.
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.yaml:snakeyaml:2.4")
}

detekt {
    toolVersion = "2.0.0-alpha.3"
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    parallel = false
    config.setFrom(files("../config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
}
