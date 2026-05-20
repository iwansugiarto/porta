import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.infinia.porta"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.infinia.porta"
        minSdk = 28
        targetSdk = 35
        versionCode = 59
        versionName = "0.4.33"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val localProps = rootProject.file("local.properties")
            val props = Properties().apply {
                if (localProps.exists()) load(localProps.reader())
            }

            storeFile = file(System.getenv("KEYSTORE_PATH") ?: props.getProperty("KEYSTORE_PATH", "../fastlane/porta-rokid.keystore"))
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("KEY_ALIAS", "porta-rokid")
            keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Project modules
    implementation(project(":shared"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Markdown
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material3)

    // WorkManager (background polling)
    implementation(libs.androidx.work.runtime)

    // Glance (home screen widget)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.material3)

    // Debug
    debugImplementation(libs.androidx.ui.tooling)
}
