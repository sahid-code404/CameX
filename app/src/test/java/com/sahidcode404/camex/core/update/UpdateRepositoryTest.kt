package com.sahidcode404.camex.core.update

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {
    @Test
    fun `network failure maps to deterministic network error`() = runTest {
        val repository = UpdateRepository(
            network = FakeNetwork(
                reads = ArrayDeque(listOf(Result.failure(IllegalStateException("offline")))),
            ),
        )

        val error = runCatching { repository.check(installed()) }.exceptionOrNull()
        assertTrue(error is UpdateException)
        assertEquals(UpdateFailureCode.NETWORK, (error as UpdateException).code)
    }

    @Test
    fun `no development prerelease returns no release`() = runTest {
        val repository = UpdateRepository(
            network = FakeNetwork(
                reads = ArrayDeque(
                    listOf(
                        Result.success(
                            """[{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[]}]""",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(UpdateCheckResult.NoRelease, repository.check(installed()))
    }

    @Test
    fun `newer development prerelease returns available manifest`() = runTest {
        val releaseJson = """
            [{
              "tag_name":"dev-ota-v101",
              "draft":false,
              "prerelease":true,
              "assets":[{
                "name":"update.json",
                "browser_download_url":"https://github.com/sahid-code404/CameX/releases/download/dev-ota-v101/update.json"
              }]
            }]
        """.trimIndent()
        val repository = UpdateRepository(
            network = FakeNetwork(
                reads = ArrayDeque(
                    listOf(
                        Result.success(releaseJson),
                        Result.success(manifestJson(versionCode = 101)),
                    ),
                ),
            ),
        )

        val result = repository.check(installed(versionCode = 100))
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(101, (result as UpdateCheckResult.Available).manifest.versionCode)
    }

    @Test
    fun `same or older prerelease is treated as up to date`() = runTest {
        val releaseJson = """
            [{
              "tag_name":"dev-ota-v100",
              "draft":false,
              "prerelease":true,
              "assets":[{
                "name":"update.json",
                "browser_download_url":"https://github.com/sahid-code404/CameX/releases/download/dev-ota-v100/update.json"
              }]
            }]
        """.trimIndent()
        val repository = UpdateRepository(
            network = FakeNetwork(
                reads = ArrayDeque(
                    listOf(
                        Result.success(releaseJson),
                        Result.success(manifestJson(versionCode = 100)),
                    ),
                ),
            ),
        )

        assertEquals(UpdateCheckResult.UpToDate, repository.check(installed(versionCode = 100)))
    }

    private fun installed(versionCode: Int = 100) = InstalledAppInfo(
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "0.1.0-dev.$versionCode",
        gitSha = "abcdef0",
        channel = "development",
        pinnedSigningCertificateSha256 = DIGEST,
        installedSigningCertificateSha256 = DIGEST,
        sdkInt = 37,
        otaEnabled = true,
    )

    private fun manifestJson(versionCode: Int): String = """
        {
          "schemaVersion": 1,
          "channel": "development",
          "packageName": "$DEVELOPMENT_PACKAGE_NAME",
          "versionCode": $versionCode,
          "versionName": "0.1.0-dev.$versionCode",
          "gitSha": "abcdef0123456789",
          "apk": {
            "name": "Camera-dev-ota.apk",
            "downloadUrl": "https://github.com/sahid-code404/CameX/releases/download/dev-ota-v$versionCode/Camera-dev-ota.apk",
            "sha256": "$DIGEST",
            "size": 1234,
            "signingCertificateSha256": "$DIGEST"
          },
          "minimumSdk": 23,
          "publishedAt": "2026-08-23T00:00:00Z",
          "mandatory": false
        }
    """.trimIndent()

    private class FakeNetwork(
        private val reads: ArrayDeque<Result<String>>,
    ) : UpdateNetworkClient {
        override suspend fun readText(url: String, maxBytes: Int): String =
            reads.removeFirst().getOrThrow()

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit,
        ) = error("download not expected")
    }

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
