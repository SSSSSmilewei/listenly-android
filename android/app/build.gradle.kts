import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String? = localProperties.getProperty(name) ?: System.getenv(name)

android {
    namespace = "cn.listenly.app"
    compileSdk = 37

    defaultConfig {
        // Same package as 2.x so 3.0 installs as an in-place update and keeps user data.
        applicationId = "cn.listenly.app"
        minSdk = 26
        targetSdk = 37
        // Must stay above 2.0.1's versionCode 16.
        versionCode = 300
        versionName = "3.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "network"
    productFlavors {
        create("standard") {
            dimension = "network"
            buildConfigField("boolean", "SYNC_ENABLED", "true")
        }
        create("offline") {
            dimension = "network"
            // No INTERNET permission is merged for this flavor (see src/standard/AndroidManifest.xml).
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "SYNC_ENABLED", "false")
        }
    }

    signingConfigs {
        // Updating an installed 2.x build requires the SAME key it was signed with.
        val keystore = secret("LISTENLY_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = secret("LISTENLY_KEYSTORE_PASSWORD")
                keyAlias = secret("LISTENLY_KEY_ALIAS")
                keyPassword = secret("LISTENLY_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Installs side by side with the real app (2.x or 3.x), so testing never touches user data.
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "拾听 测试版")
        }
        release {
            resValue("string", "app_name", "拾听")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // 2.x split-audio leftovers; the demo now plays one full recording (original-suite.wav).
        ignoreAssetsPatterns += listOf("morning.wav", "library.wav")
    }

    sourceSets {
        // Bundled original CC0 demo material (examples/demo) so a fresh install can be tried offline.
        getByName("main").assets.srcDir(rootProject.file("../examples"))
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric pokes FileDescriptor internals; JDK 21+ needs these opened explicitly.
            it.jvmArgs(
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.squircle)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    "standardImplementation"(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp)
}
