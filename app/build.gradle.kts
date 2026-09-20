import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.xiaotong6666.maihoku"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xiaotong6666.maihoku"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "**"
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.ezxhelper.core)
    implementation(libs.ezxhelper.xposed.api)
    implementation(libs.ezxhelper.android.utils)
    implementation(libs.dexkit)
}
