import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

val telegramApiIdProperty = localProperties.getProperty("telegram.api_id")
    ?.trim()
    .orEmpty()
val telegramApiHashProperty = localProperties.getProperty("telegram.api_hash")
    ?.trim()
    .orEmpty()

check(telegramApiIdProperty.isEmpty() == telegramApiHashProperty.isEmpty()) {
    "telegram.api_id and telegram.api_hash must be provided together in local.properties"
}

val telegramIdentityConfigured = telegramApiIdProperty.isNotEmpty()
val telegramApiId = if (telegramIdentityConfigured) {
    telegramApiIdProperty.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error("telegram.api_id in local.properties must be a positive integer")
} else {
    0
}
val telegramApiHash = if (telegramIdentityConfigured) telegramApiHashProperty else ""
val escapedTelegramApiHash = telegramApiHash
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val gitCommitId = rootProject.extra["gitCommitId"] as String
val gitCommitCount = rootProject.extra["gitCommitCount"] as Int
val maiHokuVersionName = "1.$gitCommitCount"

android {
    namespace = "io.github.xiaotong6666.maihoku"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xiaotong6666.maihoku"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = maiHokuVersionName

        buildConfigField("boolean", "TELEGRAM_IDENTITY_CONFIGURED", telegramIdentityConfigured.toString())
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$escapedTelegramApiHash\"")
        buildConfigField("String", "COMMIT_HASH", "\"$gitCommitId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // CI restores a fixed key to $HOME/.android/debug.keystore.
        // The default Android debug signer may resolve a different path.
        val signingHome = System.getenv("HOME") ?: System.getProperty("user.home")
        val restoredKeystore = File(signingHome, ".android/debug.keystore")
        if (restoredKeystore.isFile) {
            create("restoredDebugKey") {
                storeFile = restoredKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("restoredDebugKey")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("restoredDebugKey")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    // Keep the same billing protocol as the existing google-ultra Frida implementation.
    implementation("com.android.billingclient:billing:5.2.1")
    implementation(libs.ezxhelper.core)
    implementation(libs.dexkit)
    implementation(libs.nekogram.translator)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
