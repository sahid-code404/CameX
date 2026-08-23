package com.sahidcode404.camex.core.update

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloaderTest {
    @Test
    fun `partial file is deleted when download fails`() = runTest {
        val directory = Files.createTempDirectory("camex-ota-test").toFile()
        try {
            val downloader = UpdateDownloader(directory, FailingNetwork())
            runCatching { downloader.download(manifest()) { _, _ -> } }

            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `verified part promotes to stable apk name and removes stale candidates`() = runTest {
        val directory = Files.createTempDirectory("camex-ota-test").toFile()
        try {
            File(directory, "stale.part").writeText("stale")
            File(directory, "Camera-dev-ota.apk").writeText("old")
            val downloader = UpdateDownloader(directory, SuccessfulNetwork(ByteArray(10) { 7 }))
            val manifest = manifest(size = 10)

            val part = downloader.download(manifest) { _, _ -> }
            assertTrue(part.name.endsWith(".part"))
            val promoted = downloader.promoteVerified(part, manifest)

            assertTrue(promoted.isFile)
            assertTrue(promoted.name == "Camera-dev-ota.apk")
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun manifest(size: Long = 10) = UpdateManifest(
        schemaVersion = 1,
        channel = "development",
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = 101,
        versionName = "0.1.0-dev.101",
        gitSha = "abcdef012345",
        apk = UpdateApkManifest(
            name = "Camera-dev-ota.apk",
            downloadUrl = "https://github.com/sahid-code404/CameX/releases/download/dev-ota-v101/Camera-dev-ota.apk",
            sha256 = DIGEST,
            size = size,
            signingCertificateSha256 = DIGEST,
        ),
        minimumSdk = 23,
        publishedAt = "2026-08-23T00:00:00Z",
    )

    private class FailingNetwork : UpdateNetworkClient {
        override suspend fun readText(url: String, maxBytes: Int): String = error("not used")

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit,
        ) {
            destination.parentFile?.mkdirs()
            destination.writeBytes(ByteArray(3))
            throw IllegalStateException("offline")
        }
    }

    private class SuccessfulNetwork(private val bytes: ByteArray) : UpdateNetworkClient {
        override suspend fun readText(url: String, maxBytes: Int): String = error("not used")

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit,
        ) {
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
