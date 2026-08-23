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
val otaVersionCode = providers.environmentVariable("CAMEX_OTA_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
    ?.takeIf { it in 1 until Int.MAX_VALUE }
val otaVersionName = providers.environmentVariable("CAMEX_OTA_VERSION_NAME")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val otaSignerSha256 = providers.environmentVariable("CAMEX_DEV_SIGNING_CERT_SHA256")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val devOtaRequested = gradle.startParameter.taskNames.any { task ->
    task.contains("devOta", ignoreCase = true)
}

fun requiredDevOtaEnv(name: String): String? {
    val value = providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
    if (devOtaRequested) require(value != null) { "$name is required for devOta tasks" }
    return value
}

val devKeystorePath = requiredDevOtaEnv("CAMEX_DEV_KEYSTORE_PATH")
val devKeystorePassword = requiredDevOtaEnv("CAMEX_DEV_KEYSTORE_PASSWORD")
val devKeyAlias = requiredDevOtaEnv("CAMEX_DEV_KEY_ALIAS")
val devKeyPassword = requiredDevOtaEnv("CAMEX_DEV_KEY_PASSWORD")
if (devOtaRequested) {
    require(otaVersionCode != null) { "CAMEX_OTA_VERSION_CODE is required for devOta tasks" }
    require(otaVersionName != null) { "CAMEX_OTA_VERSION_NAME is required for devOta tasks" }
    require(otaSignerSha256?.matches(Regex("^[0-9a-fA-F]{64}$")) == true) {
        "CAMEX_DEV_SIGNING_CERT_SHA256 must be a normalized SHA-256 digest for devOta tasks"
    }
}

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
        versionCode = otaVersionCode ?: (10_000 + ciRunNumber)
        versionName = otaVersionName
            ?: if (ciRunNumber == 0) "0.1.0-dev" else "0.1.0-dev.$ciRunNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", gitSha.asBuildConfigLiteral())
        buildConfigField(
            "String",
            "BUILD_TIMESTAMP_UTC",
            buildTimestampUtc.asBuildConfigLiteral(),
        )
        buildConfigField("boolean", "OTA_ENABLED", "false")
        buildConfigField("String", "OTA_CHANNEL", "none".asBuildConfigLiteral())
        buildConfigField("String", "OTA_SIGNING_CERT_SHA256", "UNPINNED".asBuildConfigLiteral())

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
            if (devOtaRequested) {
                storeFile = file(requireNotNull(devKeystorePath))
                storePassword = requireNotNull(devKeystorePassword)
                keyAlias = requireNotNull(devKeyAlias)
                keyPassword = requireNotNull(devKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("debug")
        create("devOta") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("devOta")
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "OTA_ENABLED", "true")
            buildConfigField("String", "OTA_CHANNEL", "development".asBuildConfigLiteral())
            buildConfigField(
                "String",
                "OTA_SIGNING_CERT_SHA256",
                (otaSignerSha256 ?: "UNPINNED").lowercase().asBuildConfigLiteral(),
            )
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
