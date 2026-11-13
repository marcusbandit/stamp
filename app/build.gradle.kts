import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key lives outside the repo. scripts/bundle.sh passes the path of its
// properties file; without it, release builds use the local debug key as before.
val uploadProps = (findProperty("stamp.uploadKey") as String?)?.let { path ->
    val p = Properties().apply { file(path).inputStream().use { load(it) } }
    fun need(key: String): String = p.getProperty(key) ?: error("$path has no $key")
    mapOf(
        "storeFile" to need("storeFile"), "storePassword" to need("storePassword"),
        "keyAlias" to need("keyAlias"), "keyPassword" to need("keyPassword"),
    )
}

android {
    namespace = "com.marcusrosado.stamp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marcusrosado.stamp"
        minSdk = 26
        targetSdk = 36
        versionCode = 66
        versionName = "0.38.0"
    }

    signingConfigs {
        if (uploadProps != null) {
            create("upload") {
                storeFile = file(uploadProps.getValue("storeFile"))
                storePassword = uploadProps.getValue("storePassword")
                keyAlias = uploadProps.getValue("keyAlias")
                keyPassword = uploadProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with the local debug key so it installs over a debug build on a test
            // phone. Debug builds are several times slower in Compose; test speed on this one.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.maplibre)
    implementation(libs.androidx.window)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
