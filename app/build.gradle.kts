import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key lives outside the repo. scripts/apk.sh and scripts/bundle.sh pass the path of
// its properties file and check the signature after; without it, a release build falls back to
// the local debug key, which is only for testing speed on a test phone.
val uploadProps = (findProperty("stamp.uploadKey") as String?)?.let { path ->
    val p = Properties().apply { file(path).inputStream().use { load(it) } }
    fun need(key: String): String = p.getProperty(key) ?: error("$path has no $key")
    mapOf(
        "storeFile" to need("storeFile"), "storePassword" to need("storePassword"),
        "keyAlias" to need("keyAlias"), "keyPassword" to need("keyPassword"),
    )
}

// The Play bundle may not update itself: scripts/bundle.sh sets this, which drops the install
// permission and the update check. Play updates that copy instead.
val playBuild = findProperty("stamp.play") == "true"

android {
    namespace = "com.marcusrosado.stamp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marcusrosado.stamp"
        minSdk = 26
        targetSdk = 36
        versionCode = 101
        versionName = "1.1.0"
        buildConfigField("boolean", "SELF_UPDATE", "${!playBuild}")
    }

    if (playBuild) sourceSets.getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")

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
            // The release key when scripts/apk.sh or scripts/bundle.sh passes it, else the local
            // debug key for a fast test build. Never publish the debug-key one.
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
    implementation(libs.androidx.work)
    implementation(libs.commons.compress)
    implementation(libs.androidx.window)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}
