package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCategory
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensPreferencesState
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.Size2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSelectionPolicyTest {
    @Test
    fun `all rear canonical lenses are shown when verified rear is active`() {
        val front = lens("front", LensFacing.FRONT)
        val rear = listOf(
            lens("rear-1", LensFacing.BACK),
            lens("rear-2", LensFacing.BACK),
            lens("rear-3", LensFacing.BACK),
            lens("rear-4", LensFacing.BACK),
        )
        val active = active(rear.first())

        val projection = CameraSelectionPolicy.project(
            selectorLenses = listOf(front) + rear,
            activeSelection = active,
            sessionState = previewing(active),
        )

        assertEquals(LensFacing.BACK, projection.activeFacing)
        assertEquals(
            rear.map { it.fingerprint?.value },
            projection.lenses.map { it.fingerprint?.value },
        )
        assertEquals(LensFacing.FRONT, projection.switchTarget)
        assertTrue(projection.switchEnabled)
        assertTrue(projection.lensSelectionEnabled)
    }

    @Test
    fun `front strip contains only front canonical lenses`() {
        val front = lens("front", LensFacing.FRONT)
        val rear = listOf(
            lens("rear-1", LensFacing.BACK),
            lens("rear-2", LensFacing.BACK),
        )
        val active = active(front)

        val projection = CameraSelectionPolicy.project(
            selectorLenses = listOf(front) + rear,
            activeSelection = active,
            sessionState = previewing(active),
        )

        assertEquals(listOf(front.fingerprint?.value), projection.lenses.map { it.fingerprint?.value })
        assertEquals(LensFacing.FRONT, projection.activeFacing)
        assertEquals(LensFacing.BACK, projection.switchTarget)
    }

    @Test
    fun `unknown active state never defaults to rear or list order`() {
        val selector = listOf(
            lens("front-first", LensFacing.FRONT),
            lens("rear", LensFacing.BACK),
        )

        val projection = CameraSelectionPolicy.project(
            selectorLenses = selector,
            activeSelection = null,
            sessionState = CameraSessionState.Ready(selector.size),
        )

        assertEquals(LensFacing.UNKNOWN, projection.activeFacing)
        assertTrue(projection.lenses.isEmpty())
        assertNull(projection.switchTarget)
        assertFalse(projection.switchEnabled)
    }

    @Test
    fun `normal camera facing target is binary back front only`() {
        val selector = listOf(
            lens("back", LensFacing.BACK),
            lens("front", LensFacing.FRONT),
            lens("external", LensFacing.EXTERNAL),
            lens("unknown", LensFacing.UNKNOWN),
        )

        assertEquals(LensFacing.FRONT, CameraSelectionPolicy.targetFacing(selector, LensFacing.BACK))
        assertEquals(LensFacing.BACK, CameraSelectionPolicy.targetFacing(selector, LensFacing.FRONT))
        assertNull(CameraSelectionPolicy.targetFacing(selector, LensFacing.EXTERNAL))
        assertNull(CameraSelectionPolicy.targetFacing(selector, LensFacing.UNKNOWN))
    }

    @Test
    fun `flip and lens buttons are disabled during transition`() {
        val rear = lens("rear", LensFacing.BACK)
        val front = lens("front", LensFacing.FRONT)
        val active = active(rear)
        val switching = CameraSessionState.Switching(
            rear.identity.routingKey,
            front.identity.routingKey,
        )

        val projection = CameraSelectionPolicy.project(
            selectorLenses = listOf(rear, front),
            activeSelection = active.copy(sessionState = switching, verified = false),
            sessionState = switching,
        )

        assertFalse(projection.switchEnabled)
        assertFalse(projection.lensSelectionEnabled)
        assertNull(projection.switchTarget)
    }

    @Test
    fun `switch target restores last verified canonical lens per facing`() {
        val rear1 = lens("rear-1", LensFacing.BACK)
        val rear2 = lens("rear-2", LensFacing.BACK)
        val front1 = lens("front-1", LensFacing.FRONT)
        val front2 = lens("front-2", LensFacing.FRONT)
        val preferences = LensPreferencesState(
            lastSelectedRearFingerprint = rear2.fingerprint?.value,
            lastSelectedFrontFingerprint = front2.fingerprint?.value,
        )
        val lenses = listOf(rear1, rear2, front1, front2)

        assertEquals(
            rear2.fingerprint,
            CameraSelectionPolicy.chooseSwitchTarget(
                lenses,
                LensFacing.BACK,
                preferences,
            )?.fingerprint,
        )
        assertEquals(
            front2.fingerprint,
            CameraSelectionPolicy.chooseSwitchTarget(
                lenses,
                LensFacing.FRONT,
                preferences,
            )?.fingerprint,
        )
    }

    @Test
    fun `flip is unavailable when one phone facing is absent`() {
        val onlyRear = listOf(lens("rear", LensFacing.BACK))
        val onlyFront = listOf(lens("front", LensFacing.FRONT))

        assertNull(CameraSelectionPolicy.targetFacing(onlyRear, LensFacing.BACK))
        assertNull(CameraSelectionPolicy.targetFacing(onlyFront, LensFacing.FRONT))
    }

    private fun lens(id: String, facing: LensFacing): LensDescriptor = LensDescriptor(
        identity = LensIdentity(publicCameraId = id),
        facing = facing,
        fingerprint = LensFingerprint(
            value = "ol4_${id.replace('-', '_').padEnd(8, 'x')}",
            strategy = FingerprintStrategy.STABLE_METADATA,
        ),
        usability = LensUsability.PREVIEW_ONLY,
        category = LensCategory.PHOTOGRAPHIC_WIDE,
    )

    private fun active(lens: LensDescriptor): ActiveCameraSelection = ActiveCameraSelection(
        canonicalLensFingerprint = lens.fingerprint,
        canonicalLensId = "cl3_${lens.fingerprint?.value}",
        activeProfileRoutingKey = lens.identity.routingKey,
        activeProfileDescriptor = lens,
        facing = lens.facing,
        selectionGeneration = 1L,
        sessionState = CameraSessionState.Previewing(lens.identity.routingKey, Size2D(1920, 1080)),
        verified = true,
    )

    private fun previewing(active: ActiveCameraSelection) = CameraSessionState.Previewing(
        active.activeProfileRoutingKey,
        Size2D(1920, 1080),
    )
}
