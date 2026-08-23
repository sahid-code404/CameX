package com.sahidcode404.camex.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object UpdateNetworkPolicy {
    private const val MAX_REDIRECTS = 5
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 20_000

    fun isAllowedHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase().orEmpty()
        return normalized == "github.com" ||
            normalized == "api.github.com" ||
            normalized == "githubusercontent.com" ||
            normalized.endsWith(".githubusercontent.com")
    }

    fun validateUrl(url: String): URL {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: throw UpdateException(UpdateFailureCode.NETWORK, "Invalid update URL")
        if (uri.scheme != "https" || !isAllowedHost(uri.host)) {
            throw UpdateException(UpdateFailureCode.NETWORK, "Update URL is not an allowed HTTPS GitHub host")
        }
        return uri.toURL()
    }

    fun maxRedirects(): Int = MAX_REDIRECTS
}

class UpdateHttpStatusException(val statusCode: Int, message: String) : Exception(message)

interface UpdateNetworkClient {
    suspend fun readText(url: String, maxBytes: Int = UpdateManifestParser.MAX_MANIFEST_CHARS): String

    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    )
}

class DefaultUpdateNetworkClient : UpdateNetworkClient {
    override suspend fun readText(url: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val connection = openFollowingRedirects(url, "application/json")
        try {
            val contentLength = connection.declaredContentLength()?.takeIf { it >= 0L }
            if (contentLength != null && contentLength > maxBytes) {
                throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Remote update metadata is too large")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(contentLength?.coerceAtMost(maxBytes.toLong())?.toInt() ?: 8192)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw UpdateException(UpdateFailureCode.INVALID_MANIFEST, "Remote update metadata is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not clear stale partial update")
        }
        val connection = openFollowingRedirects(url, "application/vnd.android.package-archive")
        try {
            val total = connection.declaredContentLength()?.takeIf { it > 0L }
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.declaredContentLength(): Long? =
        getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

    private fun openFollowingRedirects(initialUrl: String, accept: String): HttpURLConnection {
        var current = UpdateNetworkPolicy.validateUrl(initialUrl)
        repeat(UpdateNetworkPolicy.maxRedirects() + 1) { redirectCount ->
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = UpdateNetworkPolicy.CONNECT_TIMEOUT_MS
            connection.readTimeout = UpdateNetworkPolicy.READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "CameX-Development-OTA")
            connection.connect()
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (redirectCount >= UpdateNetworkPolicy.maxRedirects() || location.isNullOrBlank()) {
                    throw UpdateException(UpdateFailureCode.NETWORK, "Too many or invalid update redirects")
                }
                val resolved = URL(current, location).toString()
                current = UpdateNetworkPolicy.validateUrl(resolved)
                return@repeat
            }
            val reason = connection.responseMessage.orEmpty().take(120)
            connection.disconnect()
            throw UpdateHttpStatusException(status, "HTTP $status $reason")
        }
        throw UpdateException(UpdateFailureCode.NETWORK, "Update redirect limit exceeded")
    }

    private companion object {
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
