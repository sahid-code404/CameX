package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.CameraRuntimeSnapshot
import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraTopology
import com.sahidcode404.camex.core.camera.topology.sessionRoutingKey
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint

/**
 * Verified bridge between one actually previewing transport profile and its canonical optical lens.
 * Requested selections are intentionally not exposed as active camera truth until PreviewVerified.
 */
data class ActiveCameraSelection(
    val canonicalLensFingerprint: LensFingerprint? = null,
    val canonicalLensId: String? = null,
    val activeProfileRoutingKey: String,
    val activeProfileFingerprint: String? = null,
    /** Last exact descriptor that actually produced a verified frame. */
    val activeProfileDescriptor: LensDescriptor? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
    val selectionGeneration: Long,
    val sessionState: CameraSessionState,
    val verified: Boolean,
)

data class CanonicalProfileResolution(
    val canonicalRoute: CameraRoute? = null,
    val profile: CameraProfile? = null,
    val exactSessionDescriptor: LensDescriptor? = null,
    val canonicalLensFingerprint: LensFingerprint? = null,
    val canonicalLensId: String? = null,
    val facing: LensFacing = LensFacing.UNKNOWN,
)

/**
 * One authoritative profile → canonical-lens resolver. It does not depend on the currently preferred
 * representative profile and can reconnect a verified session after topology reconciliation.
 */
object CanonicalLensResolver {
    fun resolveProfile(
        topology: CameraTopology,
        sessionSnapshot: CameraRuntimeSnapshot,
        routingKey: String?,
        profileFingerprint: String? = null,
        canonicalFingerprint: LensFingerprint? = null,
    ): CanonicalProfileResolution? {
        val normalizedRoutingKey = routingKey?.trim()?.takeIf(String::isNotEmpty)
        val normalizedProfileFingerprint = profileFingerprint?.trim()?.takeIf(String::isNotEmpty)
        val exactSessionDescriptor = normalizedRoutingKey?.let { key ->
            sessionSnapshot.lenses.firstOrNull { it.identity.routingKey == key }
        }

        val exactProfileMatch = normalizedRoutingKey?.let { key ->
            topology.routes.asSequence().mapNotNull { route ->
                route.profiles.firstOrNull { it.sessionRoutingKey() == key }?.let { route to it }
            }.firstOrNull()
        }
        val fingerprintProfileMatch = if (exactProfileMatch == null && normalizedProfileFingerprint != null) {
            topology.routes.asSequence().mapNotNull { route ->
                route.profiles.firstOrNull {
                    it.profileFingerprint == normalizedProfileFingerprint
                }?.let { route to it }
            }.firstOrNull()
        } else {
            null
        }

        val fingerprintHint = exactSessionDescriptor?.fingerprint ?: canonicalFingerprint
        val canonicalRoute = exactProfileMatch?.first
            ?: fingerprintProfileMatch?.first
            ?: fingerprintHint?.let { fingerprint ->
                topology.routes.firstOrNull { it.lensFingerprint?.value == fingerprint.value }
            }
        val profile = exactProfileMatch?.second
            ?: fingerprintProfileMatch?.second
            ?: canonicalRoute?.profiles?.firstOrNull { candidate ->
                normalizedRoutingKey != null && candidate.sessionRoutingKey() == normalizedRoutingKey
            }
            ?: canonicalRoute?.profiles?.firstOrNull { candidate ->
                normalizedProfileFingerprint != null &&
                    candidate.profileFingerprint == normalizedProfileFingerprint
            }

        val resolvedFingerprint = canonicalRoute?.lensFingerprint
            ?: exactSessionDescriptor?.fingerprint
            ?: canonicalFingerprint
        val facing = firstKnownFacing(
            exactSessionDescriptor?.facing,
            profile?.metadata?.facing,
            canonicalRoute?.minimalMetadata?.facing,
        )

        if (canonicalRoute == null && profile == null && exactSessionDescriptor == null &&
            resolvedFingerprint == null
        ) return null

        return CanonicalProfileResolution(
            canonicalRoute = canonicalRoute,
            profile = profile,
            exactSessionDescriptor = exactSessionDescriptor,
            canonicalLensFingerprint = resolvedFingerprint,
            canonicalLensId = resolvedFingerprint?.let(::canonicalLensId),
            facing = facing,
        )
    }

    private fun firstKnownFacing(vararg values: LensFacing?): LensFacing = values
        .firstOrNull { it != null && it != LensFacing.UNKNOWN }
        ?: LensFacing.UNKNOWN

    private fun canonicalLensId(fingerprint: LensFingerprint): String =
        "cl${CameraTopology.CURRENT_SCHEMA_VERSION}_${fingerprint.value}"
}

/**
 * Small state machine used by CameraRuntimeCoordinator. Explicit open/switch requests create one
 * generation. Automatic sibling-profile failover stays in that generation because it does not call
 * [beginSelection] again. A stale verified event cannot commit after a newer request because its
 * routing key no longer matches CameraRuntimeSnapshot.selectedRoutingKey.
 */
class ActiveCameraSelectionTracker {
    private data class PendingSelection(
        val generation: Long,
        val canonicalLensFingerprint: LensFingerprint?,
    )

    private var generation: Long = 0L
    private var pending: PendingSelection? = null
    private var active: ActiveCameraSelection? = null

    @Synchronized
    fun beginSelection(lens: LensDescriptor): Long {
        generation += 1L
        pending = PendingSelection(
            generation = generation,
            canonicalLensFingerprint = lens.fingerprint,
        )
        return generation
    }

    @Synchronized
    fun previewVerified(
        routingKey: String,
        sessionState: CameraSessionState,
        sessionSnapshot: CameraRuntimeSnapshot,
        topology: CameraTopology,
    ): ActiveCameraSelection? {
        if (routingKey.isBlank() || sessionSnapshot.selectedRoutingKey != routingKey) return active

        val resolution = CanonicalLensResolver.resolveProfile(
            topology = topology,
            sessionSnapshot = sessionSnapshot,
            routingKey = routingKey,
            canonicalFingerprint = pending?.canonicalLensFingerprint ?: active?.canonicalLensFingerprint,
        )
        val resolvedFingerprint = resolution?.canonicalLensFingerprint
            ?: pending?.canonicalLensFingerprint
        val pendingSelection = pending
        if (pendingSelection?.canonicalLensFingerprint != null && resolvedFingerprint != null &&
            pendingSelection.canonicalLensFingerprint.value != resolvedFingerprint.value
        ) {
            return active
        }

        val resolvedGeneration = when {
            pendingSelection != null -> pendingSelection.generation
            active != null && sameOpticalLens(active?.canonicalLensFingerprint, resolvedFingerprint) ->
                requireNotNull(active).selectionGeneration
            else -> {
                generation += 1L
                generation
            }
        }
        val next = ActiveCameraSelection(
            canonicalLensFingerprint = resolvedFingerprint,
            canonicalLensId = resolution?.canonicalLensId
                ?: resolvedFingerprint?.let {
                    "cl${CameraTopology.CURRENT_SCHEMA_VERSION}_${it.value}"
                },
            activeProfileRoutingKey = routingKey,
            activeProfileFingerprint = resolution?.profile?.profileFingerprint,
            activeProfileDescriptor = resolution?.exactSessionDescriptor,
            facing = resolution?.facing ?: LensFacing.UNKNOWN,
            selectionGeneration = resolvedGeneration,
            sessionState = sessionState,
            verified = true,
        )
        active = next
        if (pendingSelection?.generation == resolvedGeneration) pending = null
        return next
    }

    @Synchronized
    fun reconcile(
        sessionState: CameraSessionState,
        sessionSnapshot: CameraRuntimeSnapshot,
        topology: CameraTopology,
    ): ActiveCameraSelection? {
        val current = active ?: return null
        val resolution = CanonicalLensResolver.resolveProfile(
            topology = topology,
            sessionSnapshot = sessionSnapshot,
            routingKey = current.activeProfileRoutingKey,
            profileFingerprint = current.activeProfileFingerprint,
            canonicalFingerprint = current.canonicalLensFingerprint,
        ) ?: return updateSessionStateLocked(current, sessionState)

        val next = current.copy(
            canonicalLensFingerprint = resolution.canonicalLensFingerprint
                ?: current.canonicalLensFingerprint,
            canonicalLensId = resolution.canonicalLensId ?: current.canonicalLensId,
            activeProfileFingerprint = resolution.profile?.profileFingerprint
                ?: current.activeProfileFingerprint,
            activeProfileDescriptor = resolution.exactSessionDescriptor
                ?: current.activeProfileDescriptor,
            facing = resolution.facing.takeIf { it != LensFacing.UNKNOWN } ?: current.facing,
            sessionState = sessionState,
            verified = isPreviewingSameProfile(sessionState, current.activeProfileRoutingKey),
        )
        active = next
        return next
    }

    @Synchronized
    fun updateSessionState(sessionState: CameraSessionState): ActiveCameraSelection? {
        val current = active ?: return null
        return updateSessionStateLocked(current, sessionState)
    }

    @Synchronized
    fun current(): ActiveCameraSelection? = active

    private fun updateSessionStateLocked(
        current: ActiveCameraSelection,
        sessionState: CameraSessionState,
    ): ActiveCameraSelection {
        val next = current.copy(
            sessionState = sessionState,
            verified = isPreviewingSameProfile(sessionState, current.activeProfileRoutingKey),
        )
        active = next
        return next
    }

    private fun isPreviewingSameProfile(state: CameraSessionState, routingKey: String): Boolean =
        state is CameraSessionState.Previewing && state.routingKey == routingKey

    private fun sameOpticalLens(left: LensFingerprint?, right: LensFingerprint?): Boolean =
        left != null && right != null && left.value == right.value
}
