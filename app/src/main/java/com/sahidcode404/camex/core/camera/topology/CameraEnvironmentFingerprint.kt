package com.sahidcode404.camex.core.camera.topology

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Identifies the parts of the Android environment that can change camera discovery results.
 *
 * This value intentionally contains no hardware serial or user data. The optional advertised
 * signature is populated only after a cheap live scan; a missing signature remains compatible so
 * a process can consume its cache before touching CameraManager.
 */
@Serializable
data class CameraEnvironmentFingerprint(
    val cacheSchemaVersion: Int = CameraTopology.CACHE_SCHEMA_VERSION,
    val buildFingerprint: String,
    val apiLevel: Int,
    val discoverySchemaVersion: Int = CameraTopology.DISCOVERY_SCHEMA_VERSION,
    val advertisedTopologySignature: String? = null,
) {
    init {
        require(cacheSchemaVersion >= 0) { "cacheSchemaVersion must be non-negative" }
        require(apiLevel >= 0) { "apiLevel must be non-negative" }
        require(discoverySchemaVersion >= 0) { "discoverySchemaVersion must be non-negative" }
    }

    /** Exact for the mandatory environment fields, conditional for the post-startup signature. */
    fun isCompatibleWith(other: CameraEnvironmentFingerprint): Boolean =
        cacheSchemaVersion == other.cacheSchemaVersion &&
            normalizedBuildFingerprint == other.normalizedBuildFingerprint &&
            apiLevel == other.apiLevel &&
            discoverySchemaVersion == other.discoverySchemaVersion &&
            signaturesAreCompatible(other)

    /** Stable diagnostic key. Raw build strings are not used as preference keys. */
    val stableKey: String
        get() = "ce1_${sha256(canonicalValue())}"

    private val normalizedBuildFingerprint: String
        get() = buildFingerprint.trim().ifEmpty { UNKNOWN_BUILD_FINGERPRINT }

    private fun signaturesAreCompatible(other: CameraEnvironmentFingerprint): Boolean {
        val ours = advertisedTopologySignature.normalizedSignature()
        val theirs = other.advertisedTopologySignature.normalizedSignature()
        return ours == null || theirs == null || ours == theirs
    }

    private fun canonicalValue(): String = buildString {
        appendPart("cache", cacheSchemaVersion.toString())
        appendPart("build", normalizedBuildFingerprint)
        appendPart("api", apiLevel.toString())
        appendPart("discovery", discoverySchemaVersion.toString())
        appendPart("advertised", advertisedTopologySignature.normalizedSignature() ?: "?")
    }

    private fun StringBuilder.appendPart(name: String, value: String) {
        append(name.length).append(':').append(name)
        append('=').append(value.length).append(':').append(value).append(';')
    }

    private fun String?.normalizedSignature(): String? = this
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val UNKNOWN_BUILD_FINGERPRINT = "unknown-build"

        fun advertisedSignature(cameraIds: Collection<String>): String? {
            val normalized = cameraIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
            if (normalized.isEmpty()) return null
            val canonical = normalized.joinToString(separator = "|") { "${it.length}:$it" }
            return "ca1_${sha256Static(canonical)}"
        }

        private fun sha256Static(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
