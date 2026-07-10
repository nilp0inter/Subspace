package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.AnnouncementResult
import dev.nilp0inter.subspace.model.TtsModelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SystemAnnouncer(
    private val synthesizer: TtsSynthesizer
) {
    private val cache = ConcurrentHashMap<String, RecordedPcm>()
    private var activeJob: Job? = null
    private val jobMutex = Mutex()

    private val _precomputeState = MutableStateFlow<AnnouncementResult>(AnnouncementResult.WaitingForTts)
    val precomputeState: StateFlow<AnnouncementResult> = _precomputeState.asStateFlow()

    /**
     * Pre-render every phrase in [vocabulary] into non-empty SCO-ready PCM.
     * Publishes vocabulary-derived phrase progress through [precomputeState].
     *
     * Returns [AnnouncementResult.Ready] only when every required key has
     * non-empty SCO-ready PCM in the cache. A phrase counts as rendered only
     * when synthesis succeeds and produces non-empty PCM.
     *
     * On failure, returns [AnnouncementResult.Failed] identifying the failed
     * phrase and reason. The existing runtime ready-beep fallback in
     * [announce] remains a defensive behavior for later cache loss; it does
     * NOT turn partial precomputation into bootstrap success.
     */
    suspend fun precompute(
        vocabulary: Map<String, String>,
        voiceStylePath: String,
        scoRate: Int
    ): AnnouncementResult {
        val total = vocabulary.size
        if (total == 0) {
            _precomputeState.value = AnnouncementResult.Ready(emptySet())
            return _precomputeState.value as AnnouncementResult.Ready
        }

        // Wait for TTS readiness.
        _precomputeState.value = AnnouncementResult.WaitingForTts
        while (synthesizer.modelStatus != TtsModelStatus.Ready) {
            if (synthesizer.modelStatus == TtsModelStatus.Failed) {
                _precomputeState.value = AnnouncementResult.Failed(
                    completed = 0,
                    total = total,
                    failedKey = "",
                    reason = "TTS model failed to load: ${synthesizer.loadError ?: "unknown"}",
                )
                return _precomputeState.value as AnnouncementResult.Failed
            }
            delay(100)
        }

        var completed = 0
        withContext(Dispatchers.Default) {
            for ((key, text) in vocabulary) {
                _precomputeState.value = AnnouncementResult.Rendering(
                    completed = completed,
                    total = total,
                    currentKey = key,
                )
                val request = SynthesisRequest(
                    text = text,
                    voiceStylePath = voiceStylePath,
                    lang = "en",
                    totalSteps = 20,
                    speed = 1.2f
                )
                val outcome = synthesizer.synthesize(request)
                when (outcome) {
                    is SynthesisOutcome.Success -> {
                        val pcm = TtsAudio.toScoPlayback(outcome.samples, scoRate)
                        if (pcm.isEmpty) {
                            _precomputeState.value = AnnouncementResult.Failed(
                                completed = completed,
                                total = total,
                                failedKey = key,
                                reason = "Synthesis produced empty PCM for '$key'",
                            )
                            return@withContext
                        }
                        cache[key] = pcm
                    }
                    is SynthesisOutcome.ModelNotReady -> {
                        _precomputeState.value = AnnouncementResult.Failed(
                            completed = completed,
                            total = total,
                            failedKey = key,
                            reason = "TTS model not ready while rendering '$key'",
                        )
                        return@withContext
                    }
                    is SynthesisOutcome.EmptyText -> {
                        _precomputeState.value = AnnouncementResult.Failed(
                            completed = completed,
                            total = total,
                            failedKey = key,
                            reason = "Empty text for phrase '$key'",
                        )
                        return@withContext
                    }
                    is SynthesisOutcome.Failure -> {
                        _precomputeState.value = AnnouncementResult.Failed(
                            completed = completed,
                            total = total,
                            failedKey = key,
                            reason = "Synthesis failed for '$key': ${outcome.reason}",
                        )
                        return@withContext
                    }
                }
                completed++
            }
        }

        // Only publish Ready if every required key has non-empty cached PCM.
        val renderedKeys = vocabulary.keys.filter { key ->
            (cache[key]?.isEmpty == false)
        }.toSet()
        val result = if (renderedKeys.size == total) {
            AnnouncementResult.Ready(renderedKeys)
        } else {
            val missingKey = vocabulary.keys.first { it !in renderedKeys }
            AnnouncementResult.Failed(
                completed = renderedKeys.size,
                total = total,
                failedKey = missingKey,
                reason = "Phrase '$missingKey' was not rendered (empty or missing PCM)",
            )
        }
        _precomputeState.value = result
        return result
    }

    suspend fun announce(key: String, sco: ScoRoute, output: PcmOutput) = coroutineScope {
        jobMutex.withLock {
            val oldJob = activeJob
            val myJob = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val acquired = sco.acquire()
                    if (acquired) {
                        val pcm = cache[key]
                        if (pcm != null && !pcm.isEmpty) {
                            output.play(pcm)
                        } else {
                            output.playReadyBeep(coldStart = sco.coldStart)
                        }
                    }
                } finally {
                    // Only release the SCO route if no subsequent announcement has preempted us.
                    if (activeJob == coroutineContext[Job]) {
                        sco.release()
                    }
                }
            }
            activeJob = myJob
            
            // Cancel any active playback and wait for it to cleanly terminate.
            // Because activeJob was reassigned, it will not release the SCO route.
            oldJob?.cancelAndJoin()
            
            myJob.start()
        }
    }
}
