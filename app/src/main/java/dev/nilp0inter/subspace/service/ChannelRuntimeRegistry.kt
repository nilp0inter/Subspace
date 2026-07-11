package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ROUTE_LOG_TAG
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import java.util.concurrent.atomic.AtomicBoolean

class ChannelRuntimeRegistry(
    private val factories: Map<ChannelKind, ChannelRuntimeFactory>,
    private val onPttSessionCancelRequested: () -> Unit = {}
) : ChannelRouter {

    private val lock = Any()
    private val entries = mutableMapOf<String, RuntimeEntry>()
    private var orderedIds = emptyList<String>()
    private val retiredEntries = mutableListOf<RuntimeEntry>()
    private var isShutdown = false

    internal class RuntimeEntry(
        val runtime: ChannelRuntime,
        var retired: Boolean = false,
        var activeLeases: Int = 0,
        var closed: Boolean = false
    )

    private fun closeEntryLocked(entry: RuntimeEntry) {
        if (!entry.closed) {
            entry.closed = true
            entry.runtime.close()
        }
    }

    fun reconcile(snapshot: ChannelCatalogueSnapshot) {
        synchronized(lock) {
            if (isShutdown) return
            
            val nextIds = snapshot.definitions.map { it.id }.toSet()
            
            // 1. Retire/remove runtimes no longer in catalogue
            val removedIds = entries.keys.filter { it !in nextIds }
            for (id in removedIds) {
                val entry = entries.remove(id) ?: continue
                retireEntryLocked(entry)
            }
            
            // 2. Add or update/replace runtimes in catalogue
            for (def in snapshot.definitions) {
                val existing = entries[def.id]
                if (existing == null) {
                    val factory = factories[def.kind] ?: continue
                    val runtime = factory.create(def)
                    entries[def.id] = RuntimeEntry(runtime)
                } else {
                    if (existing.runtime.definition != def) {
                        // Configuration changed! Replace and retire old
                        retireEntryLocked(existing)
                        
                        val factory = factories[def.kind] ?: continue
                        val runtime = factory.create(def)
                        entries[def.id] = RuntimeEntry(runtime)
                    }
                }
            }
            orderedIds = snapshot.definitions.mapNotNull { definition ->
                entries[definition.id]?.let { definition.id }
            }
        }
    }

    private fun retireEntryLocked(entry: RuntimeEntry) {
        entry.retired = true
        if (entry.activeLeases == 0) {
            closeEntryLocked(entry)
        } else {
            retiredEntries.add(entry)
        }
    }

    override fun prepareInput(channelId: String): ChannelInputAcceptance {
        synchronized(lock) {
            if (isShutdown) {
                return ChannelInputAcceptance.Unavailable("Registry is shut down")
            }
            val entry = entries[channelId] ?: return ChannelInputAcceptance.Unavailable("Channel $channelId not found")
            if (!entry.runtime.definition.enabled) {
                return ChannelInputAcceptance.Refused("Channel $channelId is disabled")
            }
            
            val acceptance = entry.runtime.prepareInput()
            if (acceptance is ChannelInputAcceptance.Accepted) {
                entry.activeLeases++
                val originalTarget = acceptance.target
                val wrappedTarget = LeaseWrappingTarget(entry, originalTarget)
                return ChannelInputAcceptance.Accepted(wrappedTarget)
            }
            return acceptance
        }
    }

    fun getRuntime(id: String): ChannelRuntime? {
        synchronized(lock) {
            return entries[id]?.runtime
        }
    }
    fun getRuntimeSnapshot(id: String): ChannelRuntimeSnapshot? {
        synchronized(lock) {
            return entries[id]?.runtime?.snapshot?.value
        }
    }

    fun getAllRuntimeSnapshots(): List<ChannelRuntimeSnapshot> {
        synchronized(lock) {
            return orderedIds.mapNotNull { id -> entries[id]?.runtime?.snapshot?.value }
        }
    }

    fun refreshReadiness() {
        synchronized(lock) {
            for (entry in entries.values) {
                entry.runtime.refreshReadiness()
            }
        }
    }
    internal fun releaseLease(entry: RuntimeEntry) {
        synchronized(lock) {
            entry.activeLeases--
            if (entry.activeLeases == 0 && entry.retired) {
                retiredEntries.remove(entry)
                closeEntryLocked(entry)
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
            
            onPttSessionCancelRequested()
            
            for (entry in entries.values) {
                entry.retired = true
                closeEntryLocked(entry)
            }
            entries.clear()
            orderedIds = emptyList()
            
            for (entry in retiredEntries) {
                entry.retired = true
                closeEntryLocked(entry)
            }
            retiredEntries.clear()
        }
    }

    private inner class LeaseWrappingTarget(
        private val entry: RuntimeEntry,
        private val original: ChannelInputTarget
    ) : ChannelInputTarget {
        private val released = AtomicBoolean(false)

        private fun releaseLease() {
            if (released.compareAndSet(false, true)) {
                this@ChannelRuntimeRegistry.releaseLease(entry)
            }
        }

        override fun onInputStarted(session: ChannelAudioInputSession) {
            try {
                original.onInputStarted(session)
            } catch (e: Exception) {
                releaseLease()
                throw e
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            return try {
                original.onInputReleased(recording)
            } finally {
                releaseLease()
            }
        }

        override fun onInputPlaybackCompleted() {
            original.onInputPlaybackCompleted()
        }

        override fun onInputCancelled(reason: String) {
            try {
                original.onInputCancelled(reason)
            } finally {
                releaseLease()
            }
        }

        override fun onInputFailed(reason: String) {
            try {
                original.onInputFailed(reason)
            } finally {
                releaseLease()
            }
        }
    }
}
