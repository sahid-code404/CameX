package com.sahidcode404.camex.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTransitionPolicyTest {
    @Test
    fun `happy path transitions are allowed`() {
        val manifest = manifest()
        assertTrue(UpdateStateTransitionPolicy.isAllowed(UpdateState.Idle, UpdateState.Checking))
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Checking,
                UpdateState.UpdateAvailable(manifest),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.UpdateAvailable(manifest),
                UpdateState.Downloading(manifest, 0L, manifest.apk.size),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Downloading(manifest, 10L, 100L),
                UpdateState.Downloading(manifest, 20L, 100L),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Downloading(manifest, manifest.apk.size, manifest.apk.size),
                UpdateState.Verifying(manifest),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Verifying(manifest),
                UpdateState.ReadyToInstall(manifest, "/tmp/verified.apk"),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.ReadyToInstall(manifest, "/tmp/verified.apk"),
                UpdateState.Installing(manifest),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Installing(manifest),
                UpdateState.AwaitingUserAction(manifest),
            ),
        )
        assertTrue(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.AwaitingUserAction(manifest),
                UpdateState.Installed(manifest.versionCode),
            ),
        )
    }

    @Test
    fun `invalid jumps are rejected`() {
        val manifest = manifest()
        assertFalse(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Idle,
                UpdateState.Installing(manifest),
            ),
        )
        assertFalse(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Checking,
                UpdateState.ReadyToInstall(manifest, "/tmp/not-verified.apk"),
            ),
        )
        assertFalse(
            UpdateStateTransitionPolicy.isAllowed(
                UpdateState.Installed(manifest.versionCode),
                UpdateState.Checking,
            ),
        )
    }

    @Test
    fun `operational failures are allowed before install completion`() {
        val manifest = manifest()
        val failed = UpdateState.Failed(UpdateFailureCode.NETWORK, "offline")
        assertTrue(UpdateStateTransitionPolicy.isAllowed(UpdateState.Checking, failed))
        assertTrue(UpdateStateTransitionPolicy.isAllowed(UpdateState.Downloading(manifest, 1L, 10L), failed))
        assertFalse(UpdateStateTransitionPolicy.isAllowed(UpdateState.Installed(101), failed))
    }

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
            sha256 = DIGEST,
            size = 100,
            signingCertificateSha256 = DIGEST,
        ),
        minimumSdk = 23,
        publishedAt = "2026-08-23T00:00:00Z",
    )

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
