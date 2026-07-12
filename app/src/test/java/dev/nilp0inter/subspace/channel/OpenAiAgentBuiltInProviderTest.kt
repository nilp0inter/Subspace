package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisitionPolicy
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailabilityResult
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfiguration
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAgentBuiltInProviderTest {
    @Test
    fun `provider constructs an agent runtime from a validated OpenAI payload`() = runTest {
        val configuration = OpenAiAgentProviderConfiguration(
            connectionProfileId = "profile",
            modelId = "gateway-model",
            systemPrompt = "Use tools only when needed.",
            keyboardEnabled = true,
            keyboardProfileId = "desktop",
        )
        val result = OpenAiAgentBuiltInProvider().constructRuntime(
            request(payload = OpenAiAgentProviderConfigurationCodec.encode(configuration)),
        )

        assertTrue(result is ChannelRuntimeConstructionResult.Success)
        assertTrue((result as ChannelRuntimeConstructionResult.Success).runtime is OpenAiAgentRuntime)
    }

    @Test
    fun `provider maps malformed persisted payload to a typed construction failure`() = runTest {
        val result = OpenAiAgentBuiltInProvider().constructRuntime(request(opaque("""{"connectionProfileId":"profile"}""")))

        val failure = result as? ChannelRuntimeConstructionResult.Failure
            ?: throw AssertionError("Expected malformed payload construction failure, got $result")
        assertTrue(failure.error is ChannelProviderError.RuntimeConstructionFailed)
    }

    private fun request(payload: OpaqueJsonObject): ChannelRuntimeConstructionRequest = ChannelRuntimeConstructionRequest(
        definition = ChannelDefinition(
            id = "agent-instance",
            name = "Agent instance",
            implementationId = BuiltInChannelImplementationIds.OPENAI_AGENT,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = payload,
        ),
        configuration = ValidatedChannelConfiguration(
            implementationId = BuiltInChannelImplementationIds.OPENAI_AGENT,
            schemaVersion = 1,
            payload = payload,
        ),
        capabilities = UnusedCapabilities,
    )

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private object UnusedCapabilities : ChannelCapabilityScope {
        override val identity = CapabilityScopeIdentity("agent-instance", RuntimeGeneration(0))
        override val declaredCapabilities: Set<ChannelCapability> = emptySet()
        override val isClosed: Boolean = false

        override suspend fun availability(key: CapabilityKey<*>): CapabilityAvailabilityResult = CapabilityAvailabilityResult.Closed

        override suspend fun <T : ChannelCapabilityPort> acquire(
            key: CapabilityKey<T>,
            policy: CapabilityAcquisitionPolicy,
        ): CapabilityAcquisition<T> = CapabilityAcquisition.Closed
    }
}
