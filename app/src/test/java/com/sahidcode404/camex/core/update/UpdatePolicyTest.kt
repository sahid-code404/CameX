package com.sahidcode404.camex.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun `newer version with pinned signer is accepted`() {
        UpdateCandidateValidator.validateManifest(manifest(101), installed(100))
    }

    @Test
    fun `same version is rejected`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateManifest(manifest(100), installed(100))
        }
        assertEquals(UpdateFailureCode.SAME_VERSION, error.code)
    }

    @Test
    fun `downgrade is rejected`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateManifest(manifest(99), installed(100))
        }
        assertEquals(UpdateFailureCode.DOWNGRADE, error.code)
    }

    @Test
    fun `automatic checks are limited to twenty four hours`() {
        val now = 10L * UpdateCheckPolicy.AUTOMATIC_INTERVAL_MS
        assertTrue(UpdateCheckPolicy.shouldCheckAutomatically(null, now))
        assertFalse(
            UpdateCheckPolicy.shouldCheckAutomatically(
                now - UpdateCheckPolicy.AUTOMATIC_INTERVAL_MS + 1L,
                now,
            ),
        )
        assertTrue(
            UpdateCheckPolicy.shouldCheckAutomatically(
                now - UpdateCheckPolicy.AUTOMATIC_INTERVAL_MS,
                now,
            ),
        )
    }

    private fun installed(versionCode: Int) = InstalledAppInfo(
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "installed",
        gitSha = "abcdef0",
        channel = "development",
        pinnedSigningCertificateSha256 = DIGEST,
        installedSigningCertificateSha256 = DIGEST,
        sdkInt = 37,
        otaEnabled = true,
    )

    private fun manifest(versionCode: Int) = UpdateManifest(
        schemaVersion = 1,
        channel = "development",
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "0.1.0-dev.$versionCode",
        gitSha = "abcdef012345",
        apk = UpdateApkManifest(
            name = "Camera-dev-ota.apk",
            downloadUrl = "https://github.com/sahid-code404/CameX/releases/download/dev-ota-v$versionCode/Camera-dev-ota.apk",
            sha256 = DIGEST,
            size = 10,
            signingCertificateSha256 = DIGEST,
        ),
        minimumSdk = 23,
        publishedAt = "2026-08-23T00:00:00Z",
    )

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
