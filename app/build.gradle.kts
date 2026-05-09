import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun configValue(defaultValue: String, vararg keys: String): String {
    for (key in keys) {
        val localValue = localProperties.getProperty(key)
        if (!localValue.isNullOrBlank()) return localValue.trim()

        val gradleValue = project.findProperty(key)?.toString()
        if (!gradleValue.isNullOrBlank()) return gradleValue.trim()
    }
    return defaultValue.trim()
}

fun asBuildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.example.visioncamerax"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.visioncamerax"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SERVER_EMULATOR_HOST",
            asBuildConfigString(
                configValue(
                    "10.0.2.2",
                    "visioncamerax.server.emulatorHost"
                )
            )
        )
        buildConfigField(
            "String",
            "SERVER_REAL_DEVICE_HOST",
            asBuildConfigString(
                configValue(
                    "192.168.1.20",
                    "visioncamerax.server.realDeviceHost"
                )
            )
        )
        buildConfigField(
            "String",
            "SERVER_HOST_OVERRIDE",
            asBuildConfigString(
                configValue(
                    "",
                    "visioncamerax.server.host.override"
                )
            )
        )
        buildConfigField(
            "int",
            "SERVER_WS_PORT",
            configValue(
                "8770",
                "visioncamerax.server.wsPort"
            )
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
