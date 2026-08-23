package com.sahidcode404.camex.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface UpdateCheckResult {
    data object NoRelease : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult
}

class UpdateRepository(
    private val network: UpdateNetworkClient,
    private val releasesUrl: String =
        "https://api.github.com/repos/sahid-code404/CameX/releases?per_page=20",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(installed: InstalledAppInfo): UpdateCheckResult {
        val releases = try {
            val body = network.readText(releasesUrl, MAX_RELEASE_LIST_CHARS)
            json.decodeFromString<List<GitHubRelease>>(body)
        } catch (error: UpdateException) {
            throw error
        } catch (error: UpdateHttpStatusException) {
            throw UpdateException(UpdateFailureCode.NETWORK, httpFailureDetail(error), error)
        } catch (error: SerializationException) {
            throw UpdateException(UpdateFailureCode.NETWORK, "GitHub release response was invalid", error)
        } catch (error: Throwable) {
            throw UpdateException(UpdateFailureCode.NETWORK, "Could not query GitHub releases", error)
        }

        val release = releases.firstOrNull { candidate ->
            candidate.prerelease && !candidate.draft &&
                candidate.tagName.startsWith(DEVELOPMENT_RELEASE_TAG_PREFIX) &&
                candidate.assets.any { it.name == UPDATE_MANIFEST_ASSET }
        } ?: return UpdateCheckResult.NoRelease

        val asset = requireNotNull(release.assets.firstOrNull { it.name == UPDATE_MANIFEST_ASSET })
        val manifest = try {
            UpdateManifestParser.parse(network.readText(asset.browserDownloadUrl))
        } catch (error: UpdateHttpStatusException) {
            throw UpdateException(UpdateFailureCode.NETWORK, httpFailureDetail(error), error)
        } catch (error: UpdateException) {
            throw error
        } catch (error: Throwable) {
            throw UpdateException(UpdateFailureCode.NETWORK, "Could not download update metadata", error)
        }

        return try {
            UpdateCandidateValidator.validateManifest(manifest, installed)
            UpdateCheckResult.Available(manifest)
        } catch (error: UpdateException) {
            when (error.code) {
                UpdateFailureCode.SAME_VERSION,
                UpdateFailureCode.DOWNGRADE,
                -> UpdateCheckResult.UpToDate
                else -> throw error
            }
        }
    }

    private fun httpFailureDetail(error: UpdateHttpStatusException): String = when (error.statusCode) {
        403, 429 -> "GitHub update check was rate limited"
        404 -> "Development OTA release metadata was not found"
        else -> "GitHub update request failed with HTTP ${error.statusCode}"
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    private companion object {
        const val UPDATE_MANIFEST_ASSET = "update.json"
        const val MAX_RELEASE_LIST_CHARS = 2 * 1024 * 1024
    }
}
