import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.mobclaw.android.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobclaw.android.testapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        buildConfigField(
            "String",
            "LITELLM_BASE_URL",
            (localProperties.getProperty("litellm.baseUrl") ?: "https://aigw.whatsynaptic.com/v1").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "LITELLM_API_KEY",
            (localProperties.getProperty("litellm.apiKey") ?: "").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "NVIDIA_API_KEY",
            (localProperties.getProperty("nvidia.apiKey") ?: "").asBuildConfigString()
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":mobclaw"))
    implementation(project(":mobmock"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
