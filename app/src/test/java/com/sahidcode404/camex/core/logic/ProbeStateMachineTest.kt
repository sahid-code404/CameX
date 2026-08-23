package com.sahidcode404.camex.core.logic

import com.sahidcode404.camex.core.model.CapabilityFlags
import com.sahidcode404.camex.core.model.CapabilitySupport
import com.sahidcode404.camex.core.model.LensCapabilities
import com.sahidcode404.camex.core.model.LensProbeResult
import com.sahidcode404.camex.core.model.LensUsability
import com.sahidcode404.camex.core.model.ProbeFailureKind
import com.sahidcode404.camex.core.model.ProbeOutcome
import com.sahidcode404.camex.core.model.ProbeStage
import com.sahidcode404.camex.core.model.ProbeStageResult
import com.sahidcode404.camex.core.model.RawAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStateMachineTest {
    @Test
    fun processedProbeFollowsLegalShortPath() {
        val result = appendSuccesses(
            ProbeStage.DISCOVERED,
            ProbeStage.METADATA_VALID,
            ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
            ProbeStage.OPEN_SUCCESS,
            ProbeStage.PREVIEW_SUCCESS,
            ProbeStage.USABLE,
        )

        assertTrue(result.previewVerified)
        assertTrue(result.usable)
        assertFalse(result.rawConfigurationVerified)
    }

    @Test
    fun rawProbeFollowsFullPath() {
        val result = appendSuccesses(*ProbeStage.entries.toTypedArray())

        assertTrue(result.previewVerified)
        assertTrue(result.rawConfigurationVerified)
        assertTrue(result.rawFrameTested)
        assertTrue(result.usable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSkipRequiredStage() {
        ProbeStateMachine.transition(
            LensProbeResult(),
            ProbeStageResult(ProbeStage.OPEN_SUCCESS, ProbeOutcome.SUCCESS),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun coreFailureIsTerminal() {
        var result = appendSuccesses(ProbeStage.DISCOVERED)
        result = ProbeStateMachine.transition(
            result,
            ProbeStageResult(ProbeStage.METADATA_VALID, ProbeOutcome.FAILURE),
        )
        ProbeStateMachine.transition(
            result,
            ProbeStageResult(ProbeStage.SESSION_CONFIGURATION_SUPPORTED, ProbeOutcome.SUCCESS),
        )
    }

    @Test
    fun optionalRawFailureCanStillProduceUsableProcessedLens() {
        var result = appendSuccesses(
            ProbeStage.DISCOVERED,
            ProbeStage.METADATA_VALID,
            ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
            ProbeStage.OPEN_SUCCESS,
            ProbeStage.PREVIEW_SUCCESS,
        )
        result = ProbeStateMachine.transition(
            result,
            ProbeStageResult(ProbeStage.RAW_CONFIGURATION_VALID, ProbeOutcome.UNSUPPORTED),
        )
        assertTrue(
            ProbeStateMachine.canTransition(
                result,
                ProbeStageResult(ProbeStage.USABLE, ProbeOutcome.SUCCESS),
            ),
        )
    }

    @Test
    fun duplicateOrPostUsableTransitionIsRejected() {
        val result = appendSuccesses(
            ProbeStage.DISCOVERED,
            ProbeStage.METADATA_VALID,
            ProbeStage.SESSION_CONFIGURATION_SUPPORTED,
            ProbeStage.OPEN_SUCCESS,
            ProbeStage.PREVIEW_SUCCESS,
            ProbeStage.USABLE,
        )

        assertFalse(
            ProbeStateMachine.canTransition(
                result,
                ProbeStageResult(ProbeStage.USABLE, ProbeOutcome.SUCCESS),
            ),
        )
    }

    @Test
    fun usabilityClassificationSeparatesSecurityBrokenDepthAndRawAccess() {
        val system = LensCapabilities(
            flags = CapabilityFlags(systemCamera = CapabilitySupport.SUPPORTED),
        )
        assertEquals(LensUsability.SYSTEM_ONLY, LensUsabilityClassifier.classify(system, null))

        val inaccessible = LensProbeResult(
            listOf(
                ProbeStageResult(
                    ProbeStage.DISCOVERED,
                    ProbeOutcome.EXCEPTION,
                    failureKind = ProbeFailureKind.ACCESS_DENIED,
                ),
            ),
        )
        assertEquals(
            LensUsability.INACCESSIBLE,
            LensUsabilityClassifier.classify(LensCapabilities(), inaccessible),
        )

        val depth = LensCapabilities(
            flags = CapabilityFlags(
                depthOutput = CapabilitySupport.SUPPORTED,
                backwardCompatible = CapabilitySupport.UNSUPPORTED,
            ),
        )
        assertEquals(LensUsability.DEPTH_AUXILIARY, LensUsabilityClassifier.classify(depth, null))

        val rawProbe = appendSuccesses(*ProbeStage.entries.toTypedArray())
        val raw = LensCapabilities(
            flags = CapabilityFlags(raw = CapabilitySupport.SUPPORTED),
            rawAccess = RawAccess.PHYSICAL_STREAM,
        )
        assertEquals(LensUsability.RAW_PHYSICAL_STREAM, LensUsabilityClassifier.classify(raw, rawProbe))
        assertEquals(
            LensUsability.DISABLED_BY_USER,
            LensUsabilityClassifier.classify(raw, rawProbe, disabledByUser = true),
        )
    }

    private fun appendSuccesses(vararg stages: ProbeStage): LensProbeResult {
        var result = LensProbeResult()
        stages.forEach { stage ->
            result = ProbeStateMachine.transition(
                result,
                ProbeStageResult(stage, ProbeOutcome.SUCCESS),
            )
        }
        return result
    }
}
