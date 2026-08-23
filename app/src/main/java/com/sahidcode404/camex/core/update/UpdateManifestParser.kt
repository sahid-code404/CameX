package com.sahidcode404.camex.core.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object UpdateManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parse(text: String): UpdateManifest {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MANIFEST_CHARS) {
            throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Update manifest is empty or too large")
        }
        val root = try {
            json.parseToJsonElement(trimmed) as? JsonObject
                ?: invalid("Update manifest root must be an object")
        } catch (error: UpdateException) {
            throw error
        } catch (error: SerializationException) {
            throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Update manifest is not valid JSON", error)
        } catch (error: IllegalArgumentException) {
            throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Update manifest is not valid JSON", error)
        }
        val schema = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: invalid("Update manifest schemaVersion is missing")
        if (schema != DEVELOPMENT_UPDATE_SCHEMA_VERSION) {
            invalid("Unsupported update manifest schemaVersion: $schema")
        }
        val manifest = try {
            json.decodeFromString<UpdateManifest>(trimmed)
        } catch (error: SerializationException) {
            throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Update manifest fields are invalid", error)
        } catch (error: IllegalArgumentException) {
            throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Update manifest fields are invalid", error)
        }
        validate(manifest)
        return manifest.copy(
            channel = manifest.channel.trim().lowercase(),
            packageName = manifest.packageName.trim(),
            versionName = manifest.versionName.trim(),
            gitSha = manifest.gitSha.trim().lowercase(),
            apk = manifest.apk.copy(
                name = manifest.apk.name.trim(),
                downloadUrl = manifest.apk.downloadUrl.trim(),
                sha256 = normalizeSha256(manifest.apk.sha256),
                signingCertificateSha256 = normalizeSha256(manifest.apk.signingCertificateSha256),
            ),
            publishedAt = manifest.publishedAt.trim(),
            releaseNotes = manifest.releaseNotes.trim().take(MAX_RELEASE_NOTES_CHARS),
        )
    }

    private fun validate(manifest: UpdateManifest) {
        if (manifest.channel.trim().lowercase() != "development") invalid("Unsupported OTA channel")
        if (manifest.packageName.trim() != DEVELOPMENT_PACKAGE_NAME) invalid("Unexpected package name")
        if (manifest.versionCode <= 0) invalid("versionCode must be positive")
        if (manifest.versionName.isBlank() || manifest.versionName.length > 96) invalid("Invalid versionName")
        if (!manifest.gitSha.trim().matches(Regex("^[0-9a-fA-F]{7,64}$"))) invalid("Invalid git SHA")
        if (manifest.minimumSdk !in 1..10_000) invalid("Invalid minimumSdk")
        if (manifest.publishedAt.isBlank() || manifest.publishedAt.length > 64) invalid("Invalid publishedAt")
        if (manifest.apk.name != "Camera-dev-ota.apk") invalid("Unexpected APK asset name")
        if (manifest.apk.size <= 0L) invalid("APK size must be positive")
        normalizeSha256(manifest.apk.sha256)
        normalizeSha256(manifest.apk.signingCertificateSha256)
        val url = runCatching { java.net.URI(manifest.apk.downloadUrl.trim()) }.getOrNull()
            ?: invalid("Invalid APK download URL")
        if (url.scheme != "https" || !UpdateNetworkPolicy.isAllowedHost(url.host)) {
            invalid("APK download URL is not an allowed HTTPS GitHub host")
        }
    }

    private fun invalid(message: String): Nothing =
        throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, message)

    const val MAX_MANIFEST_CHARS = 256 * 1024
    const val MAX_RELEASE_NOTES_CHARS = 24 * 1024
}

fun normalizeSha256(value: String): String {
    val normalized = value.trim().replace(":", "").lowercase()
    if (!normalized.matches(Regex("^[0-9a-f]{64}$"))) {
        throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Invalid SHA-256 digest")
    }
    return normalized
}
