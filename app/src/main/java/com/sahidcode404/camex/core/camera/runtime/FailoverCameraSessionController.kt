package com.sahidcode404.camex.core.camera.runtime

import android.view.TextureView
import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionController
import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.PreviewPreferenceRegistry
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.ProbeFailureKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * Structural failure rotates through each credible profile at most once per explicit selection;
 * transient failures never cause a sibling-profile sweep.
 */
class FailoverCameraSessionController(
    private val delegate: CameraSessionController,
    private val scope: CoroutineScope,
) : CameraSessionController {
    private data class SelectionAttempt(
        val generation: Long,
        val opticalFingerprint: String,
        val attemptedRoutingKeys: LinkedHashSet<String>,
        var activeRoutingKey: String,
        var completed: Boolean = false,
    )

    private val failoverMutex = Mutex()
    private val attemptLock = Any()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(delegate.state.value)

    @Volatile
    private var availableProfiles: List<LensDescriptor> = emptyList()

    private var selectionGeneration = 0L
    private var activeAttempt: SelectionAttempt? = null

    override val state: StateFlow<CameraSessionState> = mutableState.asStateFlow()
    override val snapshot: StateFlow<CameraRuntimeSnapshot> = delegate.snapshot
    override val sessionEvents: SharedFlow<CameraSessionEvent> = delegate.sessionEvents

    init {
        // Register synchronously before the wrapper constructor returns. A Camera2 delegate can
        // publish state/events immediately after the first open; missing that first structural
        // failure would leave failover stuck on profile A.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delegate.state.collect { value ->
                mutableState.value = projectDelegateState(value)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delegate.sessionEvents.collect { event ->
                handleDelegateEvent(event)
            }
        }
    }

    override suspend fun updateAvailableLenses(lenses: List<LensDescriptor>) {
        failoverMutex.withLock {
            // Keep pristine discovered profile metadata for failover decisions. Only the delegate's
            // session-facing projection receives validated per-optical-lens preview preferences.
            val rankedProfiles = lenses.distinctBy { it.identity.routingKey }
            availableProfiles = rankedProfiles
            delegate.updateAvailableLenses(projectForSession(rankedProfiles))
        }
    }

    override suspend fun clearTransientFailureMemory(routingKey: String?) {
        // Delegate retry memory and this wrapper's per-selection attempt set are different things.
        // Clearing transient memory must never make an already-attempted structural profile eligible
        // again inside the same explicit selection generation.
        delegate.clearTransientFailureMemory(routingKey)
    }

    override suspend fun bindPreview(textureView: TextureView) {
        delegate.bindPreview(textureView)
    }

    override suspend fun unbindPreview() {
        completeActiveAttempt()
        delegate.unbindPreview()
    }

    override suspend fun open(lens: LensDescriptor) {
        selectAndOpen(lens)
    }

    override suspend fun switchTo(lens: LensDescriptor) {
        selectAndOpen(lens)
    }

    override suspend fun pause() {
        completeActiveAttempt()
        delegate.pause()
    }

    override suspend fun resume() {
        delegate.resume()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            completeActiveAttempt()
            delegate.close()
        }
    }

    /** Every public open/switch starts one new bounded failover generation. */
    private suspend fun selectAndOpen(lens: LensDescriptor) = failoverMutex.withLock {
        if (closed.get()) return@withLock

        // Preferences can change while the Camera screen is unbound. Refresh only the lightweight
        // in-memory session projection here; this performs no discovery, CameraCharacteristics IO,
        // DataStore IO, or device-specific dispatch.
        delegate.updateAvailableLenses(projectForSession(availableProfiles))

        val fingerprint = opticalKey(lens)
        // UI consumes one canonical lens descriptor whose route mirrors the preferred profile.
        // Replace it with the exact stored profile descriptor before opening so merged canonical
        // stream metadata can never leak into the selected transport profile.
        val candidate = profileForRoutingKey(lens.identity.routingKey)
            ?: availableProfiles.firstOrNull { opticalKey(it) == fingerprint }
            ?: lens
        val generation = synchronized(attemptLock) {
            selectionGeneration += 1L
            activeAttempt = SelectionAttempt(
                generation = selectionGeneration,
                opticalFingerprint = fingerprint,
                attemptedRoutingKeys = linkedSetOf(candidate.identity.routingKey),
                activeRoutingKey = candidate.identity.routingKey,
            )
            selectionGeneration
        }

        openCandidate(candidate, generation)
    }

    private suspend fun handleDelegateEvent(event: CameraSessionEvent) {
        if (closed.get()) return
        when (event) {
            is CameraSessionEvent.PreviewVerified -> {
                val matched = synchronized(attemptLock) {
                    val attempt = activeAttempt
                    if (attempt != null && !attempt.completed &&
                        attempt.activeRoutingKey == event.routingKey
                    ) {
                        attempt.completed = true
                        true
                    } else {
                        false
                    }
                }
                if (matched) mutableState.value = projectDelegateState(delegate.state.value)
            }

            is CameraSessionEvent.PreviewFailed -> {
                if (event.structural && event.kind.isStructuralProfileFailure()) {
                    failoverMutex.withLock { failOverAfter(event) }
                } else {
                    // A transient/service/lifecycle failure ends this automatic profile-selection
                    // attempt without poisoning siblings. Recovery may start a new explicit attempt.
                    val matched = synchronized(attemptLock) {
                        val attempt = activeAttempt
                        if (attempt != null && !attempt.completed &&
                            attempt.activeRoutingKey == event.routingKey
                        ) {
                            attempt.completed = true
                            true
                        } else {
                            false
                        }
                    }
                    if (matched) mutableState.value = projectDelegateState(delegate.state.value)
                }
            }

            is CameraSessionEvent.CameraOpened,
            is CameraSessionEvent.SessionConfigured,
            -> Unit
        }
    }

    private suspend fun failOverAfter(event: CameraSessionEvent.PreviewFailed) {
        if (closed.get()) return

        // Events carry a routing key but not a generation token. Requiring the delegate's current
        // observable state to still be the matching structural error prevents a delayed event from
        // an older selection from mutating a newer successful/opening attempt, even when the same
        // routing key is selected again.
        val delegateError = delegate.state.value as? CameraSessionState.ErrorRecoverable ?: return
        if (delegateError.error.routingKey != event.routingKey || delegateError.error.kind != event.kind) {
            return
        }

        val attempt = synchronized(attemptLock) {
            activeAttempt?.takeIf {
                !it.completed && it.activeRoutingKey == event.routingKey
            }
        } ?: return

        val next = synchronized(attemptLock) { nextAlternative(attempt) }
        if (next == null) {
            synchronized(attemptLock) {
                if (activeAttempt?.generation == attempt.generation) attempt.completed = true
            }
            // Only after every credible sibling has been attempted may the structural error reach
            // normal camera UI. Persistent per-profile trust is updated by CameraRuntimeCoordinator.
            mutableState.value = delegate.state.value
            return
        }

        synchronized(attemptLock) {
            val current = activeAttempt
            if (current?.generation != attempt.generation || current.completed) return
            current.attemptedRoutingKeys += next.identity.routingKey
            current.activeRoutingKey = next.identity.routingKey
        }
        openCandidate(next, attempt.generation)
    }

    private suspend fun openCandidate(lens: LensDescriptor, generation: Long) {
        val stillCurrent = synchronized(attemptLock) {
            val attempt = activeAttempt
            attempt != null && !attempt.completed && attempt.generation == generation &&
                attempt.activeRoutingKey == lens.identity.routingKey
        }
        if (!stillCurrent || closed.get()) return

        delegate.open(lens)
        // Keep transient errors immediately visible and structural errors suppressed as Switching
        // only while this same generation has an untried sibling profile.
        mutableState.value = projectDelegateState(delegate.state.value)
    }

    private fun projectDelegateState(value: CameraSessionState): CameraSessionState {
        if (value !is CameraSessionState.ErrorRecoverable ||
            !value.error.kind.isStructuralProfileFailure()
        ) return value

        val next = synchronized(attemptLock) {
            val attempt = activeAttempt
            if (attempt == null || attempt.completed ||
                attempt.activeRoutingKey != value.error.routingKey
            ) {
                null
            } else {
                nextAlternative(attempt)
            }
        }
        return next?.let {
            CameraSessionState.Switching(value.error.routingKey, it.identity.routingKey)
        } ?: value
    }

    /** Caller holds [attemptLock]. */
    private fun nextAlternative(attempt: SelectionAttempt): LensDescriptor? = availableProfiles
        .asSequence()
        .filter { opticalKey(it) == attempt.opticalFingerprint }
        .filter { it.usability.isSelectable }
        .filterNot { it.identity.routingKey in attempt.attemptedRoutingKeys }
        .firstOrNull()

    private fun projectForSession(profiles: List<LensDescriptor>): List<LensDescriptor> =
        profiles.map(PreviewPreferenceRegistry::projectForSession)

    private fun completeActiveAttempt() {
        synchronized(attemptLock) {
            activeAttempt?.completed = true
        }
    }

    private fun profileForRoutingKey(routingKey: String): LensDescriptor? =
        availableProfiles.firstOrNull { it.identity.routingKey == routingKey }

    private fun opticalKey(lens: LensDescriptor): String =
        lens.fingerprint?.value ?: "routing:${lens.identity.routingKey}"

    private fun ProbeFailureKind.isStructuralProfileFailure(): Boolean =
        this == ProbeFailureKind.INVALID_METADATA || this == ProbeFailureKind.SESSION_CONFIGURATION
}
