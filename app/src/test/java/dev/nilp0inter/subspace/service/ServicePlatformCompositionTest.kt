package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.NoopScoRoute
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.PttSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServicePlatformCompositionTest {
    @Test
    fun replacementAndRemovalPreserveCommittedTargetsWhileTerminalCleanupSuppressesLateEffectsAndShutdownClosesInOrder() = runTest {
        val provider = CompositionProvider(ChannelImplementationId("test:composition"))
        val capabilityHost = RecordingCapabilityHost()
        lateinit var shutdownTarget: dev.nilp0inter.subspace.audio.ChannelInputTarget
        val registry = compositionRegistry(provider, capabilityHost) {
            shutdownTarget.onInputCancelled("service shutdown")
            (shutdownTarget as CommittedTargetLeaseOwner).releaseCommittedTargetLease()
        }
        val original = definition("alpha", "Original", "old")
        val sibling = definition("beta", "Sibling", "sibling")
        registry.reconcile(ChannelCatalogueSnapshot(listOf(original, sibling), original.id))
        runCurrent()

        val oldRuntime = provider.runtimes.single { it.profile == "old" }
        val releaseGate = CompletableDeferred<Unit>()
        oldRuntime.releaseGate = releaseGate
        val output = RecordingOutput()
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(7, 9))
        val manager = PttAudioSessionManager(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            channelRouter = registry,
            resolvePttAudioRoute = {
                ResolvedAudioRoute(
                    sco = NoopScoRoute(),
                    output = output,
                    source = source,
                )
            },
        )

        assertTrue(manager.start(PttSource.Phone, original.id, InputMode.OnAPinch))
        runCurrent()
        assertTrue(manager.isActive)
        assertEquals(1, output.readyBeeps)

        val replacement = original.copy(
            name = "Replacement",
            configPayload = opaque("""{"stage":2,"profile":"new"}"""),
        )
        registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement, sibling), replacement.id))
        runCurrent()
        val replacementRuntime = provider.runtimes.single { it.profile == "new" }
        assertEquals(0, oldRuntime.closeCount)
        assertEquals(listOf("alpha", "beta"), registry.runtimeSnapshots.value.entries.map { it.id })

        manager.release(PttSource.Phone)
        runCurrent()
        manager.cancelActive("late competing terminal signal")
        releaseGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("released:old", "closed:old"), oldRuntime.events)
        assertEquals(1, oldRuntime.closeCount)
        assertEquals(1, output.routeReleases)
        assertEquals(
            CapabilityOperationResult.Closed,
            oldRuntime.sendLateText(),
        )
        assertTrue(capabilityHost.deliveredTexts.isEmpty())

        val futureAcceptance = registry.prepareInput(replacement.id) as? ChannelInputAcceptance.Accepted
            ?: throw AssertionError("Replacement runtime did not accept a future session")
        assertEquals(0, replacementRuntime.closeCount)
        registry.reconcile(ChannelCatalogueSnapshot(listOf(sibling), sibling.id))
        runCurrent()
        assertEquals(0, replacementRuntime.closeCount)

        futureAcceptance.target.onInputCancelled("removed after commitment")
        (futureAcceptance.target as CommittedTargetLeaseOwner).releaseCommittedTargetLease()
        advanceUntilIdle()

        assertEquals(
            listOf("cancelled:new:removed after commitment", "closed:new"),
            replacementRuntime.events,
        )
        assertEquals(1, replacementRuntime.closeCount)

        val siblingAcceptance = registry.prepareInput(sibling.id) as? ChannelInputAcceptance.Accepted
            ?: throw AssertionError("Live sibling did not accept shutdown session")
        shutdownTarget = siblingAcceptance.target
        val siblingRuntime = provider.runtimes.single { it.profile == "sibling" }
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())

        assertEquals(
            listOf("cancelled:sibling:service shutdown", "closed:sibling"),
            siblingRuntime.events,
        )
        assertEquals(1, siblingRuntime.closeCount)
        assertTrue(registry.prepareInput(sibling.id) is ChannelInputAcceptance.Unavailable)
        assertEquals(
            listOf("alpha:0:REVOKED", "alpha:1:REVOKED", "beta:0:REVOKED"),
            capabilityHost.cleanup,
        )
    }

    private fun definition(id: String, name: String, profile: String): ChannelDefinition = ChannelDefinition(
        id = id,
        name = name,
        implementationId = ChannelImplementationId("test:composition"),
        enabled = true,
        configSchemaVersion = 2,
        configPayload = opaque("""{"stage":2,"profile":"$profile"}"""),
    )
}
