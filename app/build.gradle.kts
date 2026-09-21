import java.util.Properties
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

android {
    namespace = "io.github.xiaotong6666.maihoku"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xiaotong6666.maihoku"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        buildConfigField("boolean", "TELEGRAM_IDENTITY_CONFIGURED", telegramIdentityConfigured.toString())
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$escapedTelegramApiHash\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.ezxhelper.core)
    implementation(libs.dexkit)
}
