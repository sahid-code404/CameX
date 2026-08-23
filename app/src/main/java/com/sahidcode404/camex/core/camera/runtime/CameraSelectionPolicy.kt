package com.sahidcode404.camex.core.camera.runtime

import com.sahidcode404.camex.core.camera.CameraSessionState
import com.sahidcode404.camex.core.logic.PrimaryLensSelector
import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensDescriptor
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensFingerprint
import com.sahidcode404.camex.core.model.LensPreferencesState

/** Pure projection used by CameraViewModel and unit tests. */
data class CameraLensStripProjection(
    val activeFacing: LensFacing = LensFacing.UNKNOWN,
    val lenses: List<LensDescriptor> = emptyList(),
    val selectedFingerprint: String? = null,
    val switchTarget: LensFacing? = null,
    val switchEnabled: Boolean = false,
    val lensSelectionEnabled: Boolean = false,
)

object CameraSelectionPolicy {
    fun project(
        selectorLenses: List<LensDescriptor>,
        activeSelection: ActiveCameraSelection?,
        sessionState: CameraSessionState,
    ): CameraLensStripProjection {
        val activeFacing = activeSelection?.facing ?: LensFacing.UNKNOWN
        val facingLenses = if (activeSelection == null) {
            emptyList()
        } else {
            selectorLenses.filter { it.facing == activeFacing }
        }
        val target = if (activeSelection?.verified == true) {
            targetFacing(selectorLenses, activeFacing)
        } else {
            null
        }
        val stablePreview = sessionState is CameraSessionState.Previewing &&
            activeSelection?.activeProfileRoutingKey == sessionState.routingKey
        return CameraLensStripProjection(
            activeFacing = activeFacing,
            lenses = facingLenses,
            selectedFingerprint = activeSelection?.canonicalLensFingerprint?.value,
            switchTarget = target,
            switchEnabled = stablePreview && target != null,
            lensSelectionEnabled = stablePreview,
        )
    }

    /** Ordinary phone-camera switch is strictly BACK ↔ FRONT. */
    fun targetFacing(lenses: List<LensDescriptor>, current: LensFacing): LensFacing? {
        val hasBack = lenses.any { it.facing == LensFacing.BACK && it.usability.isSelectable }
        val hasFront = lenses.any { it.facing == LensFacing.FRONT && it.usability.isSelectable }
        if (!hasBack || !hasFront) return null
        return when (current) {
            LensFacing.BACK -> LensFacing.FRONT
            LensFacing.FRONT -> LensFacing.BACK
            LensFacing.EXTERNAL, LensFacing.UNKNOWN -> null
        }
    }

    fun chooseSwitchTarget(
        lenses: List<LensDescriptor>,
        targetFacing: LensFacing,
        preferences: LensPreferencesState,
    ): LensDescriptor? {
        if (targetFacing != LensFacing.BACK && targetFacing != LensFacing.FRONT) return null
        val candidates = lenses.filter {
            it.facing == targetFacing && it.usability.isSelectable
        }
        if (candidates.isEmpty()) return null

        val lastFingerprint = when (targetFacing) {
            LensFacing.BACK -> preferences.lastSelectedRearFingerprint
            LensFacing.FRONT -> preferences.lastSelectedFrontFingerprint
            else -> null
        }
        candidates.firstOrNull { it.fingerprint?.value == lastFingerprint }?.let { return it }

        if (targetFacing == LensFacing.BACK) {
            val reference = preferences.oneXReferenceFingerprint?.let {
                LensFingerprint(it, FingerprintStrategy.STABLE_METADATA)
            }
            PrimaryLensSelector.select(candidates, reference)?.let { return it }
        }
        return candidates.maxWithOrNull(bestFacingComparator)
    }

    private val bestFacingComparator = compareBy<LensDescriptor> {
        PrimaryLensSelector.score(it).usability
    }.thenBy {
        PrimaryLensSelector.score(it).fovSuitability
    }.thenBy {
        PrimaryLensSelector.score(it).capabilityConfidence
    }.thenBy {
        PrimaryLensSelector.score(it).sensorEvidence
    }.thenBy {
        it.fingerprint?.value.orEmpty()
    }.thenBy {
        -it.discoveryOrder
    }
}
