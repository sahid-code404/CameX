package com.sahidcode404.camex.core.camera.validation

import com.sahidcode404.camex.core.camera.CameraSessionEvent
import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraMetadataTrust
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailureKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.camera.topology.CameraSessionTrust
import com.sahidcode404.camex.core.model.ProbeFailureKind

/**
 * Pure lazy-validation policy. It interprets the selected route's real session outcome; it never
 * opens a camera, enumerates routes, or initiates a speculative probe.
 */
object CameraRouteValidator {
    fun observation(
        route: CameraRoute,
        event: CameraSessionEvent,
    ): CameraRouteTrust? = when (event) {
        is CameraSessionEvent.PreviewVerified -> CameraRouteTrust(
            metadata = CameraMetadataTrust.METADATA_VALID,
            session = CameraSessionTrust.SESSION_VERIFIED,
            raw = route.trust.raw,
        )

        is CameraSessionEvent.PreviewFailed -> failureObservation(route, event)

        is CameraSessionEvent.CameraOpened,
        is CameraSessionEvent.SessionConfigured,
        -> null
    }

    private fun failureObservation(
        route: CameraRoute,
        event: CameraSessionEvent.PreviewFailed,
    ): CameraRouteTrust? {
        if (event.kind == ProbeFailureKind.PERMISSION_DENIED) return null
        val durability = if (event.structural) {
            CameraFailureDurability.STRUCTURAL
        } else {
            CameraFailureDurability.TRANSIENT
        }
        return CameraRouteTrust(
            metadata = if (event.kind == ProbeFailureKind.INVALID_METADATA) {
                CameraMetadataTrust.METADATA_REJECTED
            } else {
                route.trust.metadata
            },
            session = if (event.structural) {
                CameraSessionTrust.SESSION_REJECTED
            } else {
                CameraSessionTrust.TRANSIENT_FAILURE
            },
            raw = route.trust.raw,
            failure = CameraRouteFailure(
                kind = event.kind.toRouteFailureKind(),
                durability = durability,
                detail = event.detail,
            ),
        )
    }

    private fun ProbeFailureKind.toRouteFailureKind(): CameraRouteFailureKind = when (this) {
        ProbeFailureKind.ACCESS_DENIED, ProbeFailureKind.PERMISSION_DENIED ->
            CameraRouteFailureKind.ACCESS_DENIED
        ProbeFailureKind.SYSTEM_RESTRICTED -> CameraRouteFailureKind.SYSTEM_ONLY
        ProbeFailureKind.DISCONNECTED -> CameraRouteFailureKind.DISCONNECTED
        ProbeFailureKind.INVALID_METADATA -> CameraRouteFailureKind.INVALID_METADATA
        ProbeFailureKind.SESSION_CONFIGURATION ->
            CameraRouteFailureKind.SESSION_CONFIGURATION_UNSUPPORTED
        ProbeFailureKind.SERVICE_ERROR -> CameraRouteFailureKind.SERVICE_ERROR
        ProbeFailureKind.TIMEOUT -> CameraRouteFailureKind.TIMEOUT
        ProbeFailureKind.DEVICE_ERROR, ProbeFailureKind.UNKNOWN -> CameraRouteFailureKind.UNKNOWN
    }
}
