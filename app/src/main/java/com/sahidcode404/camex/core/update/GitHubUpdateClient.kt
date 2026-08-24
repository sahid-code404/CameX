package com.sahidcode404.camex.core.update

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
    suspend fun readText(url: String): String

    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

class HttpUpdateTransport : UpdateTransport {
    override suspend fun readText(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Camera-Android-Updater")
        }
        try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Camera-Android-Updater")
        }
        try {
            val total = connection.getHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

class GitHubUpdateClient(
    private val cacheRoot: File,
    private val transport: UpdateTransport = HttpUpdateTransport(),
    private val channel: UpdateChannel = UpdateChannel.STABLE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(installed: InstalledAppInfo): UpdateCheckResult = runCatching {
        val releaseText = try {
            transport.readText(channel.releaseUrl)
        } catch (_: FileNotFoundException) {
            // GitHub returns HTTP 404 before a channel has published its first release.
            return UpdateCheckResult.UpToDate
        }
        val release = json.decodeFromString<GitHubRelease>(releaseText)
        val manifestAsset = release.assets.firstOrNull { it.name == channel.manifestAssetName }
            ?: error("${channel.manifestAssetName} is missing")
        val manifest = json.decodeFromString<ReleaseManifest>(
            transport.readText(manifestAsset.browserDownloadUrl),
        )
        require(manifest.schema == UPDATE_MANIFEST_SCHEMA) {
            "Unsupported update manifest schema ${manifest.schema}"
        }
        if (manifest.versionCode <= installed.versionCode) {
            return UpdateCheckResult.UpToDate
        }
        if (installed.sdkInt < manifest.minSdk) {
            error("Update requires Android API ${manifest.minSdk}")
        }
        val apkAsset = release.assets.firstOrNull { it.name == manifest.apkAssetName }
            ?: error("APK asset ${manifest.apkAssetName} is missing")
        UpdateCheckResult.Available(AvailableUpdate(manifest, apkAsset.browserDownloadUrl))
    }.getOrElse { error ->
        UpdateCheckResult.Failed(error.message ?: "Update check failed")
    }

    suspend fun download(
        update: AvailableUpdate,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        val directory = File(cacheRoot, "updates").apply { mkdirs() }
        require(directory.isDirectory) { "Could not create private update cache" }
        val partFile = File(directory, "${update.manifest.apkAssetName}.part")
        partFile.delete()
        return try {
            transport.download(update.apkUrl, partFile, onProgress)
            require(partFile.isFile && partFile.length() > 0L) { "Downloaded update is empty" }
            partFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        }
    }

    fun promoteVerified(partFile: File, update: AvailableUpdate): File {
        val finalFile = File(partFile.parentFile, update.manifest.apkAssetName)
        finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        require(finalFile.isFile && finalFile.length() > 0L) { "Could not finalize verified update" }
        return finalFile
    }

    fun discard(file: File?) {
        file?.delete()
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/sahid-code404/CameX/releases/latest"
        const val DEVELOPMENT_RELEASE_URL =
            "https://api.github.com/repos/sahid-code404/CameX/releases/tags/dev-latest"
        const val MANIFEST_ASSET_NAME = "release-manifest.json"
        const val DEVELOPMENT_MANIFEST_ASSET_NAME = "dev-manifest.json"
    }
}
