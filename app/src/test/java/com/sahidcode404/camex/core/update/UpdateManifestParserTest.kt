package com.sahidcode404.camex.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestParserTest {
    @Test
    fun `valid schema one manifest parses`() {
        val manifest = UpdateManifestParser.parse(manifestJson(schemaVersion = 1))

        assertEquals(1, manifest.schemaVersion)
        assertEquals(DEVELOPMENT_PACKAGE_NAME, manifest.packageName)
        assertEquals(200001, manifest.versionCode)
        assertEquals(DIGEST, manifest.apk.sha256)
        assertEquals(DIGEST, manifest.apk.signingCertificateSha256)
    }

    @Test
    fun `future schema is rejected`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateManifestParser.parse(manifestJson(schemaVersion = 2))
        }

        assertEquals(UpdateFailureCode.INVALID_MANIFEST, error.code)
    }

    @Test
    fun `wrong package is rejected`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateManifestParser.parse(manifestJson(packageName = "com.example.other"))
        }

        assertEquals(UpdateFailureCode.INVALID_MANIFEST, error.code)
    }

    private fun manifestJson(
        schemaVersion: Int = 1,
        packageName: String = DEVELOPMENT_PACKAGE_NAME,
    ): String = """
        {
          "schemaVersion": $schemaVersion,
          "channel": "development",
          "packageName": "$packageName",
          "versionCode": 200001,
          "versionName": "0.1.0-dev.200001",
          "gitSha": "abcdef0123456789",
          "apk": {
            "name": "Camera-dev-ota.apk",
            "downloadUrl": "https://github.com/sahid-code404/CameX/releases/download/dev-ota-v200001/Camera-dev-ota.apk",
            "sha256": "$DIGEST",
            "size": 1234,
            "signingCertificateSha256": "$DIGEST"
          },
          "minimumSdk": 23,
          "publishedAt": "2026-08-23T00:00:00Z",
          "mandatory": false,
          "releaseNotes": "test"
        }
    """.trimIndent()

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
