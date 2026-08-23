package com.sahidcode404.camex.core.camera.cache

import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailureKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraProfileTrustAttemptTest {
    @Test
    fun `newer profile attempt timestamp survives trust merge`() {
        val previous = CameraRouteTrust(
            metadata = CameraMetadataTrust.METADATA_VALID,
            session = CameraSessionTrust.SESSION_VERIFIED,
            lastAttemptEpochMs = 100L,
        )
        val transient = CameraRouteTrust(
            metadata = CameraMetadataTrust.METADATA_VALID,
            session = CameraSessionTrust.TRANSIENT_FAILURE,
            failure = CameraRouteFailure(
                CameraRouteFailureKind.SERVICE_ERROR,
                CameraFailureDurability.TRANSIENT,
                "temporary",
            ),
            lastAttemptEpochMs = 250L,
        )

        val merged = CameraTrustPolicy.merge(previous, transient)

        assertEquals(CameraSessionTrust.SESSION_VERIFIED, merged.session)
        assertEquals(250L, merged.lastAttemptEpochMs)
        assertNull(merged.failure)
    }

    @Test
    fun `older replay cannot move last attempt backwards`() {
        val previous = CameraRouteTrust(lastAttemptEpochMs = 900L)
        val replayed = CameraRouteTrust(lastAttemptEpochMs = 400L)

        assertEquals(900L, CameraTrustPolicy.merge(previous, replayed).lastAttemptEpochMs)
    }
}
