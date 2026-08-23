package com.sahidcode404.camex.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraQuirkRegistryTest {
    private val environment = CameraRuntimeEnvironment("vendor", "model", 35, "build")
    private val camera = CameraRouteFacts("fingerprint", true, true, true)

    @Test
    fun genericPolicyIsUsedWhenNoExceptionMatches() {
        val registry = CameraQuirkRegistry(
            genericPolicy = CameraOperationPolicy(openTimeoutMillis = 1234),
        )

        val resolved = registry.resolve(environment, camera)

        assertEquals(1234, resolved.policy.openTimeoutMillis)
        assertTrue(resolved.policy.allowPhysicalOutputRouting)
        assertTrue(resolved.appliedRuleIds.isEmpty())
    }

    @Test
    fun matchingRuleOverridesOnlyExplicitFields() {
        val registry = CameraQuirkRegistry(
            genericPolicy = CameraOperationPolicy(openTimeoutMillis = 1234),
            rules = listOf(
                object : CameraQuirkRule {
                    override val id = "test-evidence-rule"
                    override fun matches(
                        environment: CameraRuntimeEnvironment,
                        camera: CameraRouteFacts,
                    ) = camera.opensThroughLogicalParent

                    override fun overridePolicy(
                        environment: CameraRuntimeEnvironment,
                        camera: CameraRouteFacts,
                    ) = CameraPolicyOverride(allowPhysicalOutputRouting = false)
                },
            ),
        )

        val resolved = registry.resolve(environment, camera)

        assertEquals(1234, resolved.policy.openTimeoutMillis)
        assertFalse(resolved.policy.allowPhysicalOutputRouting)
        assertEquals(listOf("test-evidence-rule"), resolved.appliedRuleIds)
    }
}
