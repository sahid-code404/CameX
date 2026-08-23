package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensProbeResult
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.RawAccess
import com.sahidcode404.camex.core.model.CapabilitySupport

/** Pure append-only validation for the conservative, sequential hardware probe. */
object ProbeStateMachine {
    fun transition(current: LensProbeResult, next: ProbeStageResult): LensProbeResult {
        require(canTransition(current, next)) {
            "Illegal probe transition from ${current.lastResult?.stage ?: "START"} to ${next.stage}"
        }
        return current.copy(stages = current.stages + next)
    }

    fun canTransition(current: LensProbeResult, next: ProbeStageResult): Boolean {
        if (current.stages.any { it.stage == next.stage }) return false
        if (current.stages.isEmpty()) return next.stage == ProbeStage.DISCOVERED
        val previous = current.lastResult ?: return false
        if (previous.stage == ProbeStage.USABLE) return false

        return when (previous.stage) {
            ProbeStage.DISCOVERED -> previous.succeeded && next.stage == ProbeStage.METADATA_VALID
            ProbeStage.METADATA_VALID -> previous.succeeded &&
                next.stage == ProbeStage.SESSION_CONFIGURATION_SUPPORTED
            ProbeStage.SESSION_CONFIGURATION_SUPPORTED -> previous.succeeded &&
                next.stage == ProbeStage.OPEN_SUCCESS
            ProbeStage.OPEN_SUCCESS -> previous.succeeded && next.stage == ProbeStage.PREVIEW_SUCCESS
            ProbeStage.PREVIEW_SUCCESS -> previous.succeeded &&
                (next.stage == ProbeStage.RAW_CONFIGURATION_VALID || next.stage == ProbeStage.USABLE)
            ProbeStage.RAW_CONFIGURATION_VALID ->
                if (previous.succeeded) {
                    next.stage == ProbeStage.RAW_TEST_SUCCESS || next.stage == ProbeStage.USABLE
                } else {
                    // RAW is optional: a working processed lens may still be marked usable.
                    next.stage == ProbeStage.USABLE
                }
            ProbeStage.RAW_TEST_SUCCESS -> next.stage == ProbeStage.USABLE
            ProbeStage.USABLE -> false
        }
    }

    private val ProbeStageResult.succeeded: Boolean get() = outcome == ProbeOutcome.SUCCESS
}

object LensUsabilityClassifier {
    fun classify(
        capabilities: LensCapabilities,
        probeResult: LensProbeResult?,
        disabledByUser: Boolean = false,
    ): LensUsability {
        if (disabledByUser) return LensUsability.DISABLED_BY_USER
        if (capabilities.flags.systemCamera == CapabilitySupport.SUPPORTED) {
            return LensUsability.SYSTEM_ONLY
        }
        if (capabilities.flags.depthOutput == CapabilitySupport.SUPPORTED &&
            capabilities.flags.backwardCompatible == CapabilitySupport.UNSUPPORTED
        ) return LensUsability.DEPTH_AUXILIARY

        val last = probeResult?.lastResult
        if (last?.failureKind == ProbeFailureKind.SYSTEM_RESTRICTED ||
            last?.failureKind == ProbeFailureKind.ACCESS_DENIED ||
            last?.failureKind == ProbeFailureKind.PERMISSION_DENIED
        ) return LensUsability.INACCESSIBLE
        if (last != null && last.outcome != ProbeOutcome.SUCCESS &&
            last.stage != ProbeStage.RAW_CONFIGURATION_VALID && last.stage != ProbeStage.RAW_TEST_SUCCESS
        ) return LensUsability.BROKEN
        if (probeResult?.previewVerified != true) {
            return if (capabilities.flags.backwardCompatible == CapabilitySupport.SUPPORTED) {
                LensUsability.PREVIEW_ONLY
            } else {
                LensUsability.UNKNOWN
            }
        }
        if (!probeResult.usable) return LensUsability.PREVIEW_ONLY
        if (!probeResult.rawConfigurationVerified || capabilities.flags.raw != CapabilitySupport.SUPPORTED) {
            return LensUsability.PROCESSED_ONLY
        }
        return when (capabilities.rawAccess) {
            RawAccess.PHYSICAL_STREAM -> LensUsability.RAW_PHYSICAL_STREAM
            RawAccess.MAXIMUM_RESOLUTION -> LensUsability.RAW_MAX_RESOLUTION
            RawAccess.DIRECT -> LensUsability.RAW_NATIVE
            RawAccess.NONE, RawAccess.UNKNOWN -> LensUsability.PROCESSED_ONLY
        }
    }
}
