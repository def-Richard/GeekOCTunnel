import java.util.Properties
import org.gradle.api.plugins.BasePluginExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

val appVersionCode = providers.gradleProperty("appVersionCode")
    .orElse(checkNotNull(versionProperties.getProperty("VERSION_CODE")))
    .get()
    .toInt()
val appVersionName = providers.gradleProperty("appVersionName")
    .orElse(checkNotNull(versionProperties.getProperty("VERSION_NAME")))
    .get()
val testAbi = providers.gradleProperty("testAbi").orElse("arm64-v8a").get()
require(testAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported ABI" }

android {
    namespace = "com.richard.tunnelkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.richard.tunnelkeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += testAbi
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

extensions.configure<BasePluginExtension> {
    archivesName.set("GeekOCTunnel-v$appVersionName-$appVersionCode" + if (testAbi == "arm64-v8a") "" else "-$testAbi")
}
