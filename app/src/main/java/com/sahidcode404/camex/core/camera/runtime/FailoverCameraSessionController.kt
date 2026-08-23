package com.sahidcode404.camex.core.camera.runtime

import android.view.TextureView
import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.ProbeFailureKind
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Profile-aware wrapper around the one authoritative CameraSessionController. It never owns a
 * CameraDevice itself. All profiles for one optical lens share the same optical fingerprint.
 * Structural failure rotates through each credible profile at most once; transient failures do
 * not cause a route storm. Recoverable-error UI is suppressed only while a structural failover is
 * actually available.
 */
class FailoverCameraSessionController(
    private val delegate: CameraSessionController,
    private val scope: CoroutineScope,
) : CameraSessionController {
    private val failoverMutex = Mutex()
    private val currentOpticalFingerprint = AtomicReference<String?>(null)
    private val mutableState = MutableStateFlow(delegate.state.value)
    private var availableProfiles: List<LensDescriptor> = emptyList()
    private val attemptedByOpticalFingerprint = linkedMapOf<String, LinkedHashSet<String>>()

    override val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()
    override val snapshot: StateFlow<CameraRuntimeSnapshot> = delegate.snapshot
    override val sessionEvents: SharedFlow<CameraSessionEvent> = delegate.sessionEvents

    init {
        scope.launch {
            delegate.state.collect { value ->
                mutableState.value = if (
                    value is CameraSessionState.ErrorRecoverable &&
                    value.error.kind.isStructuralProfileFailure() &&
                    hasUntriedAlternative(value.error.routingKey)
                ) {
                    val from = value.error.routingKey
                    val to = nextAlternative(from)?.identity?.routingKey ?: from.orEmpty()
                    CameraSessionState.Switching(from, to)
                } else {
                    value
                }
            }
        }
        scope.launch {
            delegate.sessionEvents.collect { event ->
                when (event) {
                    is CameraSessionEvent.PreviewVerified -> {
                        profileForRoutingKey(event.routingKey)?.fingerprint?.value?.let { fingerprint ->
                            attemptedByOpticalFingerprint.remove(fingerprint)
                            currentOpticalFingerprint.set(fingerprint)
                        }
                    }
                    is CameraSessionEvent.PreviewFailed -> if (event.structural) {
                        failoverMutex.withLock { failOverAfter(event.routingKey) }
                    }
                    is CameraSessionEvent.CameraOpened,
                    is CameraSessionEvent.SessionConfigured,
                    -> Unit
                }
            }
        }
    }

    override suspend fun updateAvailableLenses(lenses: List<LensDescriptor>) {
        availableProfiles = lenses.distinctBy { it.identity.routingKey }
        delegate.updateAvailableLenses(availableProfiles)
    }

    override suspend fun clearTransientFailureMemory(routingKey: String?) {
        delegate.clearTransientFailureMemory(routingKey)
    }

    override suspend fun bindPreview(textureView: TextureView) {
        delegate.bindPreview(textureView)
    }

    override suspend fun unbindPreview() {
        delegate.unbindPreview()
    }

    override suspend fun open(lens: LensDescriptor) {
        selectAndOpen(lens)
    }

    override suspend fun switchTo(lens: LensDescriptor) {
        selectAndOpen(lens)
    }

    override suspend fun pause() {
        delegate.pause()
    }

    override suspend fun resume() {
        delegate.resume()
    }

    override fun close() {
        delegate.close()
    }

    private suspend fun selectAndOpen(lens: LensDescriptor) = failoverMutex.withLock {
        val fingerprint = opticalKey(lens)
        currentOpticalFingerprint.set(fingerprint)
        attemptedByOpticalFingerprint[fingerprint] = linkedSetOf()
        openCandidate(lens, fingerprint)
    }

    private suspend fun failOverAfter(failedRoutingKey: String) {
        val failed = profileForRoutingKey(failedRoutingKey) ?: run {
            mutableState.value = delegate.state.value
            return
        }
        val fingerprint = opticalKey(failed)
        currentOpticalFingerprint.set(fingerprint)
        attemptedByOpticalFingerprint.getOrPut(fingerprint, ::linkedSetOf).add(failedRoutingKey)
        val next = nextAlternative(failedRoutingKey)
        if (next == null) {
            // Every credible profile for this optical lens has been attempted. Only now expose the
            // delegate's recoverable error to normal camera UI.
            mutableState.value = delegate.state.value
            return
        }
        openCandidate(next, fingerprint)
    }

    private suspend fun openCandidate(lens: LensDescriptor, fingerprint: String) {
        attemptedByOpticalFingerprint.getOrPut(fingerprint, ::linkedSetOf)
            .add(lens.identity.routingKey)
        delegate.open(lens)
        val state = delegate.state.value
        if (state is CameraSessionState.ErrorRecoverable &&
            state.error.kind.isStructuralProfileFailure() &&
            !hasUntriedAlternative(state.error.routingKey)
        ) {
            mutableState.value = state
        }
    }

    private fun nextAlternative(failedRoutingKey: String?): LensDescriptor? {
        val current = failedRoutingKey?.let(::profileForRoutingKey)
        val fingerprint = current?.let(::opticalKey) ?: currentOpticalFingerprint.get() ?: return null
        val attempted = attemptedByOpticalFingerprint.getOrPut(fingerprint, ::linkedSetOf)
        return availableProfiles
            .asSequence()
            .filter { opticalKey(it) == fingerprint }
            .filter { it.usability.isSelectable }
            .filterNot { it.identity.routingKey in attempted }
            .firstOrNull()
    }

    private fun hasUntriedAlternative(failedRoutingKey: String?): Boolean =
        nextAlternative(failedRoutingKey) != null

    private fun profileForRoutingKey(routingKey: String): LensDescriptor? =
        availableProfiles.firstOrNull { it.identity.routingKey == routingKey }

    private fun opticalKey(lens: LensDescriptor): String =
        lens.fingerprint?.value ?: "routing:${lens.identity.routingKey}"

    private fun ProbeFailureKind.isStructuralProfileFailure(): Boolean =
        this == ProbeFailureKind.INVALID_METADATA || this == ProbeFailureKind.SESSION_CONFIGURATION
}
