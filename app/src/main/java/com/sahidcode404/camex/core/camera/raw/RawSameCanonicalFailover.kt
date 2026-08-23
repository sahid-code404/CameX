package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.camera.topology.CameraFailureDurability
import com.sahidcode404.camex.core.camera.topology.CameraProfile
import com.sahidcode404.camex.core.camera.topology.CameraRawTrust
import com.sahidcode404.camex.core.camera.topology.CameraRoute
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailure
import com.sahidcode404.camex.core.camera.topology.CameraRouteFailureKind
import com.sahidcode404.camex.core.camera.topology.CameraRouteTrust
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensFingerprint

/** Result of one retry after the runtime has reconfigured the existing camera session. */
data class RawProfileRetry(
    val result: RawCaptureResult,
    val actualProfileFingerprint: String,
)

data class RawFailoverExecution(
    val result: RawCaptureResult,
    val attemptedProfileFingerprints: List<String>,
    val finalProfileFingerprint: String,
    val exhausted: Boolean = false,
    val abortedForSelectionChange: Boolean = false,
)

/**
 * Bounded failover for one shutter operation. Candidate selection is delegated to the existing
 * Phase 1 profile ranking and is always scoped to the original canonical optical fingerprint.
 */
class RawSameCanonicalFailoverExecutor(
    private val routes: List<CameraRoute>,
    private val canonicalFingerprint: LensFingerprint,
    initialProfileFingerprint: String,
) {
    private val attempted = linkedSetOf(initialProfileFingerprint)
    private val canonicalRoute = routes.firstOrNull {
        it.lensFingerprint?.value == canonicalFingerprint.value
    }

    suspend fun recover(
        initialResult: RawCaptureResult,
        isSelectionCurrent: () -> Boolean,
        retry: suspend (candidate: CameraProfile, attemptIndex: Int) -> RawProfileRetry,
    ): RawFailoverExecution {
        var result = initialResult
        var finalProfileFingerprint = attempted.first()
        var retryIndex = 0

        while (result.mayTriggerProfileFailover()) {
            if (!isSelectionCurrent()) {
                return aborted(result, finalProfileFingerprint)
            }

            val candidate = RawProfileFailoverPolicy.candidates(
                routes = routes,
                canonicalFingerprint = canonicalFingerprint,
                attemptedProfileFingerprints = attempted,
            ).firstOrNull() ?: return RawFailoverExecution(
                result = result,
                attemptedProfileFingerprints = attempted.toList(),
                finalProfileFingerprint = finalProfileFingerprint,
                exhausted = true,
            )

            attempted += candidate.profileFingerprint
            if (!isSelectionCurrent()) {
                return aborted(result, finalProfileFingerprint)
            }

            retryIndex += 1
            val retried = retry(candidate, retryIndex)
            result = retried.result
            finalProfileFingerprint = retried.actualProfileFingerprint
            attempted += retried.actualProfileFingerprint

            if (!belongsToOriginalCanonical(retried.actualProfileFingerprint)) {
                val failed = RawCaptureResult.Failed(
                    reason = "RAW retry resolved outside the original canonical lens",
                    structural = false,
                    diagnostics = result.diagnostics().copy(
                        lastRawError = "cross-canonical retry rejected",
                    ),
                    failureKind = RawFailureKind.STALE_SELECTION,
                )
                return RawFailoverExecution(
                    result = failed,
                    attemptedProfileFingerprints = attempted.toList(),
                    finalProfileFingerprint = finalProfileFingerprint,
                    abortedForSelectionChange = true,
                )
            }

            if (!isSelectionCurrent()) {
                return aborted(result, finalProfileFingerprint)
            }
        }

        return RawFailoverExecution(
            result = result,
            attemptedProfileFingerprints = attempted.toList(),
            finalProfileFingerprint = finalProfileFingerprint,
        )
    }

    private fun belongsToOriginalCanonical(profileFingerprint: String): Boolean =
        canonicalRoute?.profiles?.any { it.profileFingerprint == profileFingerprint } == true

    private fun aborted(
        latestResult: RawCaptureResult,
        finalProfileFingerprint: String,
    ): RawFailoverExecution {
        val failed = RawCaptureResult.Failed(
            reason = "Camera selection changed during RAW profile failover",
            structural = false,
            diagnostics = latestResult.diagnostics().copy(
                lastRawError = "stale selection generation",
            ),
            failureKind = RawFailureKind.STALE_SELECTION,
        )
        return RawFailoverExecution(
            result = failed,
            attemptedProfileFingerprints = attempted.toList(),
            finalProfileFingerprint = finalProfileFingerprint,
            abortedForSelectionChange = true,
        )
    }
}

fun RawCaptureResult.mayTriggerProfileFailover(): Boolean = when (this) {
    is RawCaptureResult.Saved -> false
    is RawCaptureResult.Failed -> structural && failureKind in STRUCTURAL_RAW_FAILURES
}

fun RawCaptureResult.diagnostics(): RawCaptureDiagnostics = when (this) {
    is RawCaptureResult.Saved -> diagnostics
    is RawCaptureResult.Failed -> diagnostics
}

/** Profile-scoped evidence only; storage/DNG/transient failures intentionally return no update. */
object RawProfileTrustObserver {
    fun observation(
        route: CameraRoute,
        result: RawCaptureResult,
        attemptEpochMs: Long = System.currentTimeMillis(),
    ): CameraRouteTrust? = when (result) {
        is RawCaptureResult.Saved -> CameraRouteTrust(
            metadata = route.trust.metadata,
            session = route.trust.session,
            raw = CameraRawTrust.RAW_VERIFIED,
            failure = route.trust.failure?.takeUnless {
                it.kind == CameraRouteFailureKind.RAW_CONFIGURATION_UNSUPPORTED
            },
            lastAttemptEpochMs = attemptEpochMs.coerceAtLeast(0L),
        )

        is RawCaptureResult.Failed -> if (result.mayTriggerProfileFailover()) {
            CameraRouteTrust(
                metadata = route.trust.metadata,
                session = route.trust.session,
                raw = if (
                    result.failureKind == RawFailureKind.CAPABILITY_UNAVAILABLE &&
                    route.minimalMetadata.rawCapabilityAdvertised == CapabilitySupport.UNSUPPORTED
                ) {
                    CameraRawTrust.NOT_ADVERTISED
                } else {
                    CameraRawTrust.RAW_REJECTED
                },
                failure = CameraRouteFailure(
                    kind = CameraRouteFailureKind.RAW_CONFIGURATION_UNSUPPORTED,
                    durability = CameraFailureDurability.STRUCTURAL,
                    detail = result.reason,
                ).normalized(),
                lastAttemptEpochMs = attemptEpochMs.coerceAtLeast(0L),
            )
        } else {
            null
        }
    }
}

private val STRUCTURAL_RAW_FAILURES = setOf(
    RawFailureKind.CAPABILITY_UNAVAILABLE,
    RawFailureKind.RAW_SIZE_UNAVAILABLE,
    RawFailureKind.SESSION_CONFIGURATION,
    RawFailureKind.CAPTURE_REQUEST_REJECTED,
)
