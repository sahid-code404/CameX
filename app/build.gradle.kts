import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull
    ?.toIntOrNull()
    ?.coerceIn(0, 2_000_000_000)
    ?: 0
val gitSha = providers.environmentVariable("CAMEX_GIT_SHA")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .getOrElse("unknown")
val buildTimestampUtc = providers.environmentVariable("CAMEX_BUILD_TIMESTAMP_UTC")
    .getOrElse("unknown")
val taggedVersionCode = providers.gradleProperty("cameraVersionCode")
    .orNull
    ?.toIntOrNull()
    ?.takeIf { it in 1 until Int.MAX_VALUE }
val taggedVersionName = providers.gradleProperty("cameraVersionName")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val devOtaVersionCode = providers.gradleProperty("devOtaVersionCode")
    .orNull
    ?.toIntOrNull()
    ?.takeIf { it in 1 until Int.MAX_VALUE }
val devOtaVersionName = providers.gradleProperty("devOtaVersionName")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

val devOtaKeystoreBase64File = rootProject.file("tools/dev-signing/camex-dev.jks.b64")
val devOtaKeystoreFile = layout.buildDirectory.file("dev-signing/camex-dev.jks").get().asFile
if (devOtaKeystoreBase64File.isFile) {
    val encoded = devOtaKeystoreBase64File.readText().filterNot(Char::isWhitespace)
    val decoded = Base64.getDecoder().decode(encoded)
    devOtaKeystoreFile.parentFile.mkdirs()
    if (!devOtaKeystoreFile.isFile || !devOtaKeystoreFile.readBytes().contentEquals(decoded)) {
        devOtaKeystoreFile.writeBytes(decoded)
    }
}
val devOtaSigningReady = devOtaKeystoreFile.isFile && devOtaKeystoreFile.length() > 0L

val devOtaStorePassword = "camex-dev-only-2026"
val devOtaKeyAlias = "camex-dev"
val devOtaKeyPassword = "camex-dev-only-2026"

fun String.asBuildConfigLiteral(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.sahidcode404.camex"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.sahidcode404.camex"
        minSdk = 23
        targetSdk = 37
        versionCode = devOtaVersionCode ?: taggedVersionCode ?: (10_000 + ciRunNumber)
        versionName = devOtaVersionName
            ?: taggedVersionName
            ?: if (ciRunNumber == 0) "0.1.0-dev" else "0.1.0-dev.$ciRunNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", gitSha.asBuildConfigLiteral())
        buildConfigField(
            "String",
            "BUILD_TIMESTAMP_UTC",
            buildTimestampUtc.asBuildConfigLiteral(),
        )
        buildConfigField("String", "OTA_CHANNEL", "stable".asBuildConfigLiteral())

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    signingConfigs {
        create("devOta") {
            check(devOtaSigningReady) {
                "Development OTA keystore is missing. Expected tools/dev-signing/camex-dev.jks.b64"
            }
            storeFile = devOtaKeystoreFile
            storePassword = devOtaStorePassword
            keyAlias = devOtaKeyAlias
            keyPassword = devOtaKeyPassword
        }
        create("stableRelease") {
            if (releaseSigningReady) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug")
        create("devOta") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("devOta")
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "OTA_CHANNEL", "development".asBuildConfigLiteral())
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/core/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
