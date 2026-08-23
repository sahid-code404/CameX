package com.sahidcode404.camex.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

private const val MAX_JSON_BYTES = 256 * 1024
private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
private val VERSION_NAME_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
private val APK_NAME_PATTERN = Regex("^[A-Za-z0-9._-]+\\.apk$")

@Serializable
private data class GitHubRelease(
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
)

interface UpdateTransport {
    suspend fun readText(url: String, maxBytes: Int = MAX_JSON_BYTES): String

    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

class HttpUpdateTransport : UpdateTransport {
    override suspend fun readText(url: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val connection = open(url, "application/vnd.github+json")
        try {
            val output = ByteArrayOutputStream(8 * 1024)
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw UpdateException(UpdateFailureCode.NETWORK, "GitHub update metadata is too large")
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = open(url, "application/vnd.android.package-archive")
        try {
            val totalBytes = connection.getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, totalBytes)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val parsed = URI(url.trim())
        require(parsed.scheme == "https" && isGitHubHost(parsed.host)) {
            "Update URL must use an allowed GitHub HTTPS host"
        }
        return (parsed.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CameX-Android-Updater")
            connect()
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw UpdateException(UpdateFailureCode.NETWORK, "GitHub returned HTTP $code")
            }
        }
    }

    private fun isGitHubHost(host: String?): Boolean {
        val normalized = host?.lowercase().orEmpty()
        return normalized == "api.github.com" ||
            normalized == "github.com" ||
            normalized == "githubusercontent.com" ||
            normalized.endsWith(".githubusercontent.com")
    }
}

class GitHubUpdateClient(
    private val cacheRoot: File,
    private val transport: UpdateTransport = HttpUpdateTransport(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(installed: InstalledAppInfo): UpdateCheckResult {
        return try {
            val release = json.decodeFromString<GitHubRelease>(transport.readText(LATEST_RELEASE_URL))
            val manifestAsset = release.assets.firstOrNull { it.name == MANIFEST_ASSET_NAME }
                ?: return UpdateCheckResult.Failed("release-manifest.json is missing from the latest release")
            val manifest = json.decodeFromString<ReleaseManifest>(
                transport.readText(manifestAsset.browserDownloadUrl),
            )
            validateManifestStructure(manifest)
            if (manifest.versionCode <= installed.versionCode) return UpdateCheckResult.UpToDate
            if (installed.sdkInt < manifest.minSdk) {
                return UpdateCheckResult.Failed("Update requires Android API ${manifest.minSdk}")
            }
            val installedSigner = installed.signingCertificateSha256
                ?: return UpdateCheckResult.Failed("Installed Camera signing certificate is unavailable")
            if (!manifest.signingCertSha256.equals(installedSigner, ignoreCase = true)) {
                return UpdateCheckResult.Failed("Release signing certificate does not match installed Camera")
            }
            val apkAsset = release.assets.firstOrNull { it.name == manifest.apkAssetName }
                ?: return UpdateCheckResult.Failed("APK asset ${manifest.apkAssetName} is missing")
            UpdateCheckResult.Available(
                AvailableUpdate(
                    manifest = manifest,
                    apkUrl = apkAsset.browserDownloadUrl,
                ),
            )
        } catch (error: SerializationException) {
            UpdateCheckResult.Failed("Malformed GitHub release metadata")
        } catch (error: IllegalArgumentException) {
            UpdateCheckResult.Failed(error.message ?: "Invalid GitHub release metadata")
        } catch (error: UpdateException) {
            UpdateCheckResult.Failed(error.message ?: "Update check failed")
        } catch (error: Exception) {
            UpdateCheckResult.Failed("Update check failed: ${error.javaClass.simpleName}")
        }
    }

    suspend fun download(
        update: AvailableUpdate,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        validateManifestStructure(update.manifest)
        val directory = File(cacheRoot, "updates").apply { mkdirs() }
        if (!directory.isDirectory) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not create private update cache")
        }
        val finalFile = File(directory, update.manifest.apkAssetName)
        val partFile = File(directory, "${update.manifest.apkAssetName}.part")
        if (partFile.exists() && !partFile.delete()) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not clear previous partial update")
        }
        try {
            transport.download(update.apkUrl, partFile, onProgress)
            if (!partFile.isFile || partFile.length() <= 0L) {
                throw UpdateException(UpdateFailureCode.NETWORK, "Downloaded update is empty")
            }
            return partFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        }
    }

    fun promoteVerified(partFile: File, update: AvailableUpdate): File {
        val finalFile = File(partFile.parentFile, update.manifest.apkAssetName)
        if (finalFile.exists() && !finalFile.delete()) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not replace previous verified update")
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            if (!partFile.delete()) finalFile.delete()
        }
        if (!finalFile.isFile || finalFile.length() <= 0L) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not finalize verified update")
        }
        return finalFile
    }

    fun discard(file: File?) {
        file?.takeIf(File::exists)?.delete()
    }

    internal fun validateManifestStructure(manifest: ReleaseManifest) {
        require(manifest.schema == UPDATE_MANIFEST_SCHEMA) {
            "Unsupported update manifest schema ${manifest.schema}"
        }
        require(manifest.versionCode > 0L) { "Invalid update versionCode" }
        require(VERSION_NAME_PATTERN.matches(manifest.versionName)) { "Invalid update versionName" }
        require(manifest.minSdk > 0) { "Invalid update minSdk" }
        require(APK_NAME_PATTERN.matches(manifest.apkAssetName)) { "Invalid update APK asset name" }
        require(SHA256_PATTERN.matches(manifest.sha256)) { "Invalid update APK SHA-256" }
        require(SHA256_PATTERN.matches(manifest.signingCertSha256)) {
            "Invalid update signing certificate SHA-256"
        }
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/sahid-code404/CameX/releases/latest"
        const val MANIFEST_ASSET_NAME = "release-manifest.json"
    }
}
