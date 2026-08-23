package com.sahidcode404.camex.core.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubUpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val signer = "a".repeat(64)
    private val manifestUrl = "https://github.com/sahid-code404/CameX/releases/download/v0.1.2/release-manifest.json"
    private val apkUrl = "https://github.com/sahid-code404/CameX/releases/download/v0.1.2/Camera-0.1.2.apk"

    @Test
    fun `newer version is available and APK asset comes from manifest`() = runTest {
        val client = clientFor(
            releaseJson = releaseJson(includeManifest = true, includeApk = true),
            manifestJson = manifestJson(versionCode = 1002),
        )
        val result = client.check(installed(versionCode = 1001))
        assertTrue(result is UpdateCheckResult.Available)
        result as UpdateCheckResult.Available
        assertTrue(result.update.apkUrl == apkUrl)
        assertTrue(result.update.manifest.apkAssetName == "Camera-0.1.2.apk")
    }

    @Test
    fun `same version is up to date`() = runTest {
        val result = clientFor(releaseJson(), manifestJson(1001)).check(installed(1001))
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `older version is up to date`() = runTest {
        val result = clientFor(releaseJson(), manifestJson(1000)).check(installed(1001))
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `malformed GitHub release fails`() = runTest {
        val transport = FakeTransport(mapOf(GitHubUpdateClient.LATEST_RELEASE_URL to "not-json"))
        val result = GitHubUpdateClient(temporaryFolder.root, transport).check(installed())
        assertTrue(result is UpdateCheckResult.Failed)
    }

    @Test
    fun `missing manifest asset fails`() = runTest {
        val client = clientFor(releaseJson(includeManifest = false, includeApk = true), manifestJson(1002))
        val result = client.check(installed())
        assertTrue(result is UpdateCheckResult.Failed)
        result as UpdateCheckResult.Failed
        assertTrue(result.message.contains("release-manifest.json"))
    }

    @Test
    fun `missing APK asset fails`() = runTest {
        val client = clientFor(releaseJson(includeManifest = true, includeApk = false), manifestJson(1002))
        val result = client.check(installed())
        assertTrue(result is UpdateCheckResult.Failed)
        result as UpdateCheckResult.Failed
        assertTrue(result.message.contains("APK asset"))
    }

    @Test
    fun `download failure removes partial file`() = runTest {
        val transport = FakeTransport(
            texts = emptyMap(),
            downloadAction = { destination ->
                destination.parentFile?.mkdirs()
                destination.writeText("partial")
                throw IOException("network lost")
            },
        )
        val client = GitHubUpdateClient(temporaryFolder.root, transport)
        val update = AvailableUpdate(
            manifest = manifest(versionCode = 1002),
            apkUrl = apkUrl,
        )
        runCatching { client.download(update) { _, _ -> } }
        val part = File(temporaryFolder.root, "updates/Camera-0.1.2.apk.part")
        assertFalse(part.exists())
    }

    private fun clientFor(releaseJson: String, manifestJson: String): GitHubUpdateClient {
        val transport = FakeTransport(
            mapOf(
                GitHubUpdateClient.LATEST_RELEASE_URL to releaseJson,
                manifestUrl to manifestJson,
            ),
        )
        return GitHubUpdateClient(temporaryFolder.root, transport)
    }

    private fun releaseJson(
        includeManifest: Boolean = true,
        includeApk: Boolean = true,
    ): String {
        val assets = buildList {
            if (includeManifest) add("{\"name\":\"release-manifest.json\",\"browser_download_url\":\"$manifestUrl\"}")
            if (includeApk) add("{\"name\":\"Camera-0.1.2.apk\",\"browser_download_url\":\"$apkUrl\"}")
        }
        return "{\"assets\":[${assets.joinToString(",")}]}"
    }

    private fun manifestJson(versionCode: Long): String =
        """{"schema":1,"versionCode":$versionCode,"versionName":"0.1.2","minSdk":23,"apkAssetName":"Camera-0.1.2.apk","sha256":"${"b".repeat(64)}","signingCertSha256":"$signer","changelog":"notes","mandatory":false}"""

    private fun manifest(versionCode: Long): ReleaseManifest = ReleaseManifest(
        schema = 1,
        versionCode = versionCode,
        versionName = "0.1.2",
        minSdk = 23,
        apkAssetName = "Camera-0.1.2.apk",
        sha256 = "b".repeat(64),
        signingCertSha256 = signer,
    )

    private fun installed(versionCode: Long = 1001): InstalledAppInfo = InstalledAppInfo(
        packageName = UPDATE_PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "0.1.1",
        gitSha = "test",
        signingCertificateSha256 = signer,
        sdkInt = 35,
    )

    private class FakeTransport(
        private val texts: Map<String, String>,
        private val downloadAction: suspend (File) -> Unit = { error("download not expected") },
    ) : UpdateTransport {
        override suspend fun readText(url: String, maxBytes: Int): String =
            texts[url] ?: error("No fake response for $url")

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit,
        ) {
            downloadAction(destination)
        }
    }
}
