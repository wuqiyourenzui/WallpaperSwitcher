plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wallpaperswitcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wallpaperswitcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the same (original) debug key so the optimized build
            // installs **over** the debug build on the tablet and keeps updating
            // in place (no uninstall, no data loss). Swap in a real release
            // keystore before handing this APK to anyone else.
            signingConfig = signingConfigs.getByName("debug")
            // Kept debuggable on purpose: the runtime log (cache/logs/runtime.log)
            // and the database stay readable through `adb run-as`, which is how
            // OEM-specific issues are diagnosed on the tablet. R8 minification,
            // shrinking and optimization all still apply. Flip to false before
            // distributing the APK outside this machine.
            isDebuggable = true
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Pin the animation core version: M3 1.2.x progress indicators call
    // KeyframesSpecConfig.at(...), which only exists in animation-core 1.6.0.
    // A transitive dependency resolving to an older version crashed with
    // NoSuchMethodError as soon as any CircularProgressIndicator was shown.
    implementation("androidx.compose.animation:animation-core:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")  // 视频帧缩略图

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}
