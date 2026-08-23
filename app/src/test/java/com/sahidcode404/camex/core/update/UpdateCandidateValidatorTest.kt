package com.sahidcode404.camex.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCandidateValidatorTest {
    private val signer = "a".repeat(64)
    private val sha = "b".repeat(64)

    @Test
    fun `correct package hash version and signer pass`() {
        UpdateCandidateValidator.validate(
            manifest = manifest(),
            installed = installed(),
            inspection = inspection(),
            actualSha256 = sha,
        )
    }

    @Test
    fun `hash mismatch is rejected`() {
        assertFailure(UpdateFailureCode.HASH_MISMATCH) {
            UpdateCandidateValidator.validate(manifest(), installed(), inspection(), "c".repeat(64))
        }
    }

    @Test
    fun `package mismatch is rejected`() {
        assertFailure(UpdateFailureCode.PACKAGE_MISMATCH) {
            UpdateCandidateValidator.validate(
                manifest(),
                installed(),
                inspection().copy(packageName = "com.example.other"),
                sha,
            )
        }
    }

    @Test
    fun `signer mismatch is rejected`() {
        assertFailure(UpdateFailureCode.SIGNATURE_MISMATCH) {
            UpdateCandidateValidator.validate(
                manifest(),
                installed(),
                inspection().copy(signingCertificateSha256 = "c".repeat(64)),
                sha,
            )
        }
    }

    @Test
    fun `same version is rejected before install`() {
        assertFailure(UpdateFailureCode.SAME_VERSION) {
            UpdateCandidateValidator.validate(
                manifest().copy(versionCode = 1001),
                installed(),
                inspection().copy(versionCode = 1001),
                sha,
            )
        }
    }

    @Test
    fun `downgrade is rejected before install`() {
        assertFailure(UpdateFailureCode.DOWNGRADE) {
            UpdateCandidateValidator.validate(
                manifest().copy(versionCode = 1000),
                installed(),
                inspection().copy(versionCode = 1000),
                sha,
            )
        }
    }

    private fun assertFailure(expected: UpdateFailureCode, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull() as UpdateException
        assertEquals(expected, error.code)
    }

    private fun manifest() = ReleaseManifest(
        schema = 1,
        versionCode = 1002,
        versionName = "0.1.2",
        minSdk = 23,
        apkAssetName = "Camera-0.1.2.apk",
        sha256 = sha,
        signingCertSha256 = signer,
    )

    private fun installed() = InstalledAppInfo(
        packageName = UPDATE_PACKAGE_NAME,
        versionCode = 1001,
        versionName = "0.1.1",
        gitSha = "test",
        signingCertificateSha256 = signer,
        sdkInt = 35,
    )

    private fun inspection() = ApkInspection(
        packageName = UPDATE_PACKAGE_NAME,
        versionCode = 1002,
        signingCertificateSha256 = signer,
    )
}
