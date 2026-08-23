package com.sahidcode404.camex.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateCandidateValidatorTest {
    @Test
    fun `package mismatch is rejected before install`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateDownloaded(
                manifest = manifest(),
                installed = installed(),
                inspection = inspection(packageName = "com.example.other"),
                actualSha256 = APK_DIGEST,
            )
        }

        assertEquals(UpdateFailureCode.PACKAGE_MISMATCH, error.code)
    }

    @Test
    fun `hash mismatch is rejected before install`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateDownloaded(
                manifest = manifest(),
                installed = installed(),
                inspection = inspection(),
                actualSha256 = OTHER_DIGEST,
            )
        }

        assertEquals(UpdateFailureCode.HASH_MISMATCH, error.code)
    }

    @Test
    fun `certificate mismatch is rejected before install`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateDownloaded(
                manifest = manifest(),
                installed = installed(),
                inspection = inspection(signer = OTHER_DIGEST),
                actualSha256 = APK_DIGEST,
            )
        }

        assertEquals(UpdateFailureCode.SIGNATURE_MISMATCH, error.code)
    }

    @Test
    fun `correct package version hash and certificate are accepted`() {
        UpdateCandidateValidator.validateDownloaded(
            manifest = manifest(),
            installed = installed(),
            inspection = inspection(),
            actualSha256 = APK_DIGEST,
        )
    }

    @Test
    fun `manifest signer must match pinned signer`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateCandidateValidator.validateManifest(
                manifest = manifest().copy(
                    apk = manifest().apk.copy(signingCertificateSha256 = OTHER_DIGEST),
                ),
                installed = installed(),
            )
        }

        assertEquals(UpdateFailureCode.SIGNATURE_MISMATCH, error.code)
    }

    private fun installed() = InstalledAppInfo(
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = 100,
        versionName = "0.1.0-dev.100",
        gitSha = "abcdef0",
        channel = "development",
        pinnedSigningCertificateSha256 = SIGNER_DIGEST,
        installedSigningCertificateSha256 = SIGNER_DIGEST,
        sdkInt = 37,
        otaEnabled = true,
    )

    private fun manifest() = UpdateManifest(
        schemaVersion = 1,
        channel = "development",
        packageName = DEVELOPMENT_PACKAGE_NAME,
        versionCode = 101,
        versionName = "0.1.0-dev.101",
        gitSha = "abcdef012345",
        apk = UpdateApkManifest(
            name = "Camera-dev-ota.apk",
            downloadUrl = "https://github.com/sahid-code404/CameX/releases/download/dev-ota-v101/Camera-dev-ota.apk",
            sha256 = APK_DIGEST,
            size = 10,
            signingCertificateSha256 = SIGNER_DIGEST,
        ),
        minimumSdk = 23,
        publishedAt = "2026-08-23T00:00:00Z",
    )

    private fun inspection(
        packageName: String = DEVELOPMENT_PACKAGE_NAME,
        signer: String = SIGNER_DIGEST,
    ) = ApkInspection(
        packageName = packageName,
        versionCode = 101,
        signingCertificateSha256 = signer,
    )

    private companion object {
        const val APK_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SIGNER_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
