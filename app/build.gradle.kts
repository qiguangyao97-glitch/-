plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.gongderefuser"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.gongderefuser"
        minSdk = 28
        targetSdk = 28
        versionCode = 29
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("sharedDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "gongdebug"
            keyAlias = "gongdebug"
            keyPassword = "gongdebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            signingConfig = signingConfigs.getByName("sharedDebug")
            optimization {
                enable = false
            }
        }
    }
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            resValue("string", "app_name", "功德拒絕器")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            resValue("string", "app_name", "功德拒絕器 測試版")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

tasks.matching {
    it.name.startsWith("processBeta") && it.name.endsWith("GoogleServices")
}.configureEach {
    enabled = false
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.firestore)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
