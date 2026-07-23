package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audiofile.ExecutionOwner
import dev.nilp0inter.subspace.audiofile.ExecutionOwnerKind
import dev.nilp0inter.subspace.audiofile.PcmMonoS16Le
import dev.nilp0inter.subspace.audiofile.RecordingBorrow
import dev.nilp0inter.subspace.audiofile.RecordingHandle
import dev.nilp0inter.subspace.audiofile.RecordingHost
import dev.nilp0inter.subspace.audiofile.hostToken
import dev.nilp0inter.subspace.audiofile.recordingHandle
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.recordedPcmOf

/**
 * Adapts the state-local opaque Lua audio registry to the language-neutral
 * [RecordingHost] contract used by [dev.nilp0inter.subspace.audiofile.AudioFilePort].
 * The two handle types intentionally carry the same random host token; neither
 * exposes it outside this composition boundary.
 */
internal class LuaRecordingHost(
    private val registry: LuaOpaqueAudioRegistry,
) : RecordingHost {
    override fun borrow(handle: RecordingHandle, owner: ExecutionOwner): RecordingBorrow {
        val resolution = registry.resolve(
            token = LuaOpaqueAudioRegistry.Token(handle.hostToken()),
            owner = owner.luaOwner(),
            kind = LuaOpaqueAudioRegistry.Kind.Captured,
        )
        return when (resolution) {
            is LuaOpaqueAudioRegistry.Resolution.Captured -> {
                val pcm = recordedPcmOf(resolution.recording)
                    ?: return RecordingBorrow.Stale
                RecordingBorrow.Borrowed(PcmMonoS16Le(pcm.samples, pcm.sampleRate))
            }
            LuaOpaqueAudioRegistry.Resolution.Foreign -> RecordingBorrow.Foreign
            LuaOpaqueAudioRegistry.Resolution.Closed -> RecordingBorrow.Closed
            LuaOpaqueAudioRegistry.Resolution.Stale,
            LuaOpaqueAudioRegistry.Resolution.WrongKind,
            is LuaOpaqueAudioRegistry.Resolution.Synthesized -> RecordingBorrow.Stale
        }
    }

    override fun admit(pcm: PcmMonoS16Le, owner: ExecutionOwner): RecordingHandle? {
        val recording = opaqueAudioRecording(
            RecordedPcm(pcm.samples, pcm.sampleRate),
            RuntimeGeneration(owner.generation),
        )
        val token = registry.admitCaptured(owner.luaOwner(), recording) ?: return null
        return recordingHandle(token.value)
    }

    override fun dispose(handle: RecordingHandle) {
        registry.dispose(
            LuaOpaqueAudioRegistry.Token(handle.hostToken()),
            LuaOpaqueAudioRegistry.Kind.Captured,
        )
    }

    fun handleFor(token: LuaOpaqueAudioRegistry.Token): RecordingHandle = recordingHandle(token.value)

    fun tokenFor(handle: RecordingHandle): String = handle.hostToken()

    private fun ExecutionOwner.luaOwner(): LuaOpaqueAudioRegistry.Owner = when (kind) {
        ExecutionOwnerKind.INPUT -> LuaOpaqueAudioRegistry.Owner.Input(id)
        ExecutionOwnerKind.TASK -> LuaOpaqueAudioRegistry.Owner.Task(id)
    }
}
