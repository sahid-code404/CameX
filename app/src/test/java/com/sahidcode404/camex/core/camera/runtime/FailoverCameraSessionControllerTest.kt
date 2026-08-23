package com.sahidcode404.camex.core.camera.runtime

import android.view.TextureView
import com.sahidcode404.camex.core.camera.CameraRuntimeError
import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensIdentity
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.Size2D
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverCameraSessionControllerTest {
    @Test
    fun `structural failures attempt each profile once until sibling succeeds`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf(
                "a" to ArrayDeque(listOf(Outcome.STRUCTURAL)),
                "b" to ArrayDeque(listOf(Outcome.STRUCTURAL)),
                "c" to ArrayDeque(listOf(Outcome.SUCCESS)),
            ),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        controller.updateAvailableLenses(profiles)
        runCurrent()

        controller.open(profiles.first())
        runCurrent()

        assertEquals(listOf("a", "b", "c"), delegate.openedIds)
        assertEquals(3, delegate.openedIds.distinct().size)
        assertTrue(controller.state.value is CameraSessionState.Previewing)
        assertEquals("c", controller.snapshot.value.selectedLens?.identity?.openCameraId)
    }

    @Test
    fun `transient failure does not sweep sibling profiles`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf("a" to ArrayDeque(listOf(Outcome.TRANSIENT))),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        controller.updateAvailableLenses(profiles)
        runCurrent()

        controller.open(profiles.first())
        runCurrent()

        assertEquals(listOf("a"), delegate.openedIds)
        assertTrue(controller.state.value is CameraSessionState.ErrorRecoverable)
    }

    @Test
    fun `canonical descriptor resolves to exact stored profile metadata before open`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf("preferred" to ArrayDeque(listOf(Outcome.SUCCESS))),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val exactProfile = profile("preferred", pixelWidth = 4000)
        controller.updateAvailableLenses(listOf(exactProfile))
        runCurrent()

        val canonicalDescriptor = exactProfile.copy(
            capabilities = exactProfile.capabilities.copy(pixelArraySize = Size2D(8000, 6000)),
        )
        controller.open(canonicalDescriptor)
        runCurrent()

        assertEquals(4000, delegate.openedLenses.single().capabilities.pixelArraySize?.width)
    }

    @Test
    fun `new lens selection resets bounded attempt set without creating retry loop`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf(
                "a" to ArrayDeque(listOf(Outcome.STRUCTURAL, Outcome.SUCCESS)),
                "b" to ArrayDeque(listOf(Outcome.STRUCTURAL)),
            ),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val profiles = listOf(profile("a"), profile("b"))
        controller.updateAvailableLenses(profiles)
        runCurrent()

        controller.open(profiles.first())
        runCurrent()
        assertEquals(listOf("a", "b"), delegate.openedIds)
        assertTrue(controller.state.value is CameraSessionState.ErrorRecoverable)

        controller.open(profiles.first())
        runCurrent()
        assertEquals(listOf("a", "b", "a"), delegate.openedIds)
        assertTrue(controller.state.value is CameraSessionState.Previewing)
        assertFalse(delegate.openedIds.takeLast(2) == listOf("b", "a") && delegate.openedIds.size > 3)
    }

    @Test
    fun `fresh canonical selection opens preferred profile first`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf("b" to ArrayDeque(listOf(Outcome.SUCCESS))),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val rejectedAlias = profile("a")
        val preferred = profile("b", pixelWidth = 4000)
        controller.updateAvailableLenses(listOf(preferred, rejectedAlias))
        runCurrent()

        val canonicalDescriptor = preferred.copy(
            capabilities = preferred.capabilities.copy(pixelArraySize = Size2D(8000, 6000)),
        )
        controller.open(canonicalDescriptor)
        runCurrent()

        assertEquals(listOf("b"), delegate.openedIds)
        assertEquals(4000, delegate.openedLenses.single().capabilities.pixelArraySize?.width)
    }

    @Test
    fun `verified preview terminates attempt and stale structural event cannot trigger failover`() = runTest {
        val delegate = FakeSessionController(
            outcomes = mapOf(
                "a" to ArrayDeque(listOf(Outcome.SUCCESS, Outcome.SUCCESS)),
                "b" to ArrayDeque(listOf(Outcome.SUCCESS)),
            ),
        )
        val controller = FailoverCameraSessionController(delegate, backgroundScope)
        val profiles = listOf(profile("a"), profile("b"))
        controller.updateAvailableLenses(profiles)
        runCurrent()

        controller.open(profiles.first())
        runCurrent()
        controller.open(profiles.first())
        runCurrent()

        delegate.emitStaleStructuralFailure(profiles.first())
        runCurrent()

        assertEquals(listOf("a", "a"), delegate.openedIds)
        assertTrue(controller.state.value is CameraSessionState.Previewing)
    }

    private fun profile(id: String, pixelWidth: Int = 4000): LensDescriptor = LensDescriptor(
        identity = LensIdentity(publicCameraId = id),
        facing = LensFacing.BACK,
        capabilities = LensCapabilities(pixelArraySize = Size2D(pixelWidth, 3000)),
        fingerprint = opticalFingerprint,
        usability = LensUsability.PREVIEW_ONLY,
    )

    private enum class Outcome { SUCCESS, STRUCTURAL, TRANSIENT }

    private class FakeSessionController(
        private val outcomes: Map<String, ArrayDeque<Outcome>>,
    ) : CameraSessionController {
        private val mutableState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
        private val mutableSnapshot = MutableStateFlow(CameraRuntimeSnapshot.Empty)
        private val events = MutableSharedFlow<CameraSessionEvent>(extraBufferCapacity = 16)
        val openedLenses = mutableListOf<LensDescriptor>()
        val openedIds: List<String> get() = openedLenses.map { it.identity.openCameraId }

        override val state: StateFlow<CameraSessionState> = mutableState
        override val snapshot: StateFlow<CameraRuntimeSnapshot> = mutableSnapshot
        override val sessionEvents: SharedFlow<CameraSessionEvent> = events

        override suspend fun updateAvailableLenses(lenses: List<LensDescriptor>) {
            mutableSnapshot.value = mutableSnapshot.value.copy(lenses = lenses)
            mutableState.value = CameraSessionState.Ready(lenses.size)
        }

        override suspend fun clearTransientFailureMemory(routingKey: String?) = Unit
        override suspend fun bindPreview(textureView: TextureView) = Unit
        override suspend fun unbindPreview() = Unit

        override suspend fun open(lens: LensDescriptor) {
            openedLenses += lens
            val key = lens.identity.routingKey
            mutableSnapshot.value = mutableSnapshot.value.copy(
                selectedRoutingKey = key,
                lastError = null,
            )
            when (outcomes[lens.identity.openCameraId]?.pollFirst() ?: Outcome.SUCCESS) {
                Outcome.SUCCESS -> {
                    mutableState.value = CameraSessionState.Previewing(key, Size2D(1920, 1080))
                    events.emit(CameraSessionEvent.PreviewVerified(key))
                }
                Outcome.STRUCTURAL -> fail(
                    lens,
                    ProbeFailureKind.SESSION_CONFIGURATION,
                    structural = true,
                )
                Outcome.TRANSIENT -> fail(
                    lens,
                    ProbeFailureKind.SERVICE_ERROR,
                    structural = false,
                )
            }
        }

        override suspend fun switchTo(lens: LensDescriptor) = open(lens)
        override suspend fun pause() {
            mutableState.value = CameraSessionState.Paused(mutableSnapshot.value.selectedRoutingKey)
        }
        override suspend fun resume() = Unit
        override fun close() {
            mutableState.value = CameraSessionState.Closed
        }

        suspend fun emitStaleStructuralFailure(lens: LensDescriptor) {
            events.emit(
                CameraSessionEvent.PreviewFailed(
                    routingKey = lens.identity.routingKey,
                    kind = ProbeFailureKind.SESSION_CONFIGURATION,
                    structural = true,
                    detail = "stale synthetic failure",
                ),
            )
        }

        private suspend fun fail(
            lens: LensDescriptor,
            kind: ProbeFailureKind,
            structural: Boolean,
        ) {
            val key = lens.identity.routingKey
            val error = CameraRuntimeError(kind, "synthetic failure", key)
            mutableSnapshot.value = mutableSnapshot.value.copy(lastError = error)
            mutableState.value = CameraSessionState.ErrorRecoverable(error)
            events.emit(
                CameraSessionEvent.PreviewFailed(
                    routingKey = key,
                    kind = kind,
                    structural = structural,
                    detail = "synthetic failure",
                ),
            )
        }
    }

    private companion object {
        val opticalFingerprint = LensFingerprint(
            value = "ol3_failover-test",
            strategy = FingerprintStrategy.STABLE_METADATA,
        )
    }
}
