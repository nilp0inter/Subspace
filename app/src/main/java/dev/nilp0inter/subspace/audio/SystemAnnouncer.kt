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
import kotlinx.coroutines.CancellationException

class SystemAnnouncer(
    private val synthesizer: TtsSynthesizer,
    private val persistentCache: AnnouncementPcmCache? = null
) {
    private val cache = ConcurrentHashMap<String, RecordedPcm>()
    private var activeJob: Job? = null
    private val jobMutex = Mutex()
    private val precomputeMutex = Mutex()

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
    ): AnnouncementResult = precomputeMutex.withLock {
        var hitsCount = 0
        var missesCount = 0
        var synthesesCount = 0
        var commitStr = "skipped"
        var outcomeStr = "failed"

        try {
            cache.clear()
            if (vocabulary.isEmpty()) {
                val settings = AnnouncementRenderSettings("", "en", 20, 1.2f, scoRate)
                val commitResult = persistentCache?.commit(emptyMap(), settings, emptyMap())
                commitStr = when (commitResult) {
                    AnnouncementCacheCommitResult.Unchanged -> "unchanged"
                    AnnouncementCacheCommitResult.Written -> "written"
                    AnnouncementCacheCommitResult.Skipped -> "skipped"
                    is AnnouncementCacheCommitResult.Failed -> "failed"
                    null -> "skipped"
                }
                val finalResult = AnnouncementResult.Ready(emptySet())
                _precomputeState.value = finalResult
                outcomeStr = "ready"
                return finalResult
            }

            // Wait for TTS readiness.
            _precomputeState.value = AnnouncementResult.WaitingForTts
            while (synthesizer.modelStatus != TtsModelStatus.Ready) {
                if (synthesizer.modelStatus == TtsModelStatus.Failed) {
                    val finalResult = AnnouncementResult.Failed(
                        completed = 0,
                        total = vocabulary.size,
                        failedKey = "",
                        reason = "TTS model failed to load: ${synthesizer.loadError ?: "unknown"}",
                    )
                    _precomputeState.value = finalResult
                    outcomeStr = "failed"
                    return finalResult
                }
                delay(100)
            }

            val settings = AnnouncementRenderSettings(
                voiceStylePath = voiceStylePath,
                lang = "en",
                totalSteps = 20,
                speed = 1.2f,
                scoRate = scoRate
            )


            val hits = persistentCache?.load(vocabulary, settings) ?: emptyMap()
            for ((key, pcm) in hits) {
                if (!pcm.isEmpty) {
                    cache[key] = pcm
                }
            }

            hitsCount = cache.size
            val misses = vocabulary.filterKeys { !cache.containsKey(it) }
            missesCount = misses.size

            if (misses.isEmpty()) {
                val renderedKeys = vocabulary.keys.toSet()
                val commitResult = persistentCache?.commit(vocabulary, settings, cache) ?: AnnouncementCacheCommitResult.Skipped
                commitStr = when (commitResult) {
                    AnnouncementCacheCommitResult.Unchanged -> "unchanged"
                    AnnouncementCacheCommitResult.Written -> "written"
                    AnnouncementCacheCommitResult.Skipped -> "skipped"
                    is AnnouncementCacheCommitResult.Failed -> "failed"
                }
                val finalResult = AnnouncementResult.Ready(renderedKeys)
                _precomputeState.value = finalResult
                outcomeStr = "ready"
                return finalResult
            }

            val groups = LinkedHashMap<String, ArrayList<String>>()
            for ((key, text) in misses) {
                groups.getOrPut(text) { ArrayList() }.add(key)
            }

            var readyLogicalKeyCount = cache.size
            val total = vocabulary.size

            withContext(Dispatchers.Default) {
                for ((text, groupKeys) in groups) {
                    _precomputeState.value = AnnouncementResult.Rendering(
                        completed = readyLogicalKeyCount,
                        total = total,
                        currentKey = groupKeys[0]
                    )

                    val request = SynthesisRequest(
                        text = text,
                        voiceStylePath = voiceStylePath,
                        lang = "en",
                        totalSteps = 20,
                        speed = 1.2f
                    )

                    synthesesCount++
                    val outcome = synthesizer.synthesize(request)
                    when (outcome) {
                        is SynthesisOutcome.Success -> {
                            val pcm = TtsAudio.toScoPlayback(outcome.samples, scoRate)
                            if (pcm.isEmpty) {
                                val finalResult = AnnouncementResult.Failed(
                                    completed = readyLogicalKeyCount,
                                    total = total,
                                    failedKey = groupKeys[0],
                                    reason = "Synthesis produced empty PCM for '${groupKeys[0]}'"
                                )
                                _precomputeState.value = finalResult
                                outcomeStr = "failed"
                                return@withContext
                            }
                            for (key in groupKeys) {
                                cache[key] = pcm
                            }
                            readyLogicalKeyCount += groupKeys.size
                        }
                        is SynthesisOutcome.ModelNotReady -> {
                            val finalResult = AnnouncementResult.Failed(
                                completed = readyLogicalKeyCount,
                                total = total,
                                failedKey = groupKeys[0],
                                reason = "TTS model not ready while rendering '${groupKeys[0]}'"
                            )
                            _precomputeState.value = finalResult
                            outcomeStr = "failed"
                            return@withContext
                        }
                        is SynthesisOutcome.EmptyText -> {
                            val finalResult = AnnouncementResult.Failed(
                                completed = readyLogicalKeyCount,
                                total = total,
                                failedKey = groupKeys[0],
                                reason = "Empty text for phrase '${groupKeys[0]}'"
                            )
                            _precomputeState.value = finalResult
                            outcomeStr = "failed"
                            return@withContext
                        }
                        is SynthesisOutcome.Failure -> {
                            val finalResult = AnnouncementResult.Failed(
                                completed = readyLogicalKeyCount,
                                total = total,
                                failedKey = groupKeys[0],
                                reason = "Synthesis failed for '${groupKeys[0]}': ${outcome.reason}"
                            )
                            _precomputeState.value = finalResult
                            outcomeStr = "failed"
                            return@withContext
                        }
                    }
                }
            }

            val renderedKeys = vocabulary.keys.filter { key ->
                (cache[key]?.isEmpty == false)
            }.toSet()

            if (renderedKeys.size == total) {
                val commitResult = persistentCache?.commit(vocabulary, settings, cache) ?: AnnouncementCacheCommitResult.Skipped
                commitStr = when (commitResult) {
                    AnnouncementCacheCommitResult.Unchanged -> "unchanged"
                    AnnouncementCacheCommitResult.Written -> "written"
                    AnnouncementCacheCommitResult.Skipped -> "skipped"
                    is AnnouncementCacheCommitResult.Failed -> "failed"
                }
                val finalResult = AnnouncementResult.Ready(renderedKeys)
                _precomputeState.value = finalResult
                outcomeStr = "ready"
                return finalResult
            } else {
                val missingKey = vocabulary.keys.first { it !in renderedKeys }
                val finalResult = AnnouncementResult.Failed(
                    completed = renderedKeys.size,
                    total = total,
                    failedKey = missingKey,
                    reason = "Phrase '$missingKey' was not rendered (empty or missing PCM)"
                )
                _precomputeState.value = finalResult
                outcomeStr = "failed"
                return finalResult
            }
        } catch (e: CancellationException) {
            outcomeStr = "cancelled"
            commitStr = "skipped"
            throw e
        } catch (e: Exception) {
            outcomeStr = "failed"
            commitStr = "skipped"
            val finalResult = AnnouncementResult.Failed(
                completed = cache.size,
                total = vocabulary.size,
                failedKey = "",
                reason = e.message ?: "Unknown error"
            )
            _precomputeState.value = finalResult
            return finalResult
        } finally {
            val msg = "ANNOUNCEMENT_CACHE_SUMMARY hits=$hitsCount misses=$missesCount syntheses=$synthesesCount commit=$commitStr outcome=$outcomeStr"
            try {
                android.util.Log.i("SystemAnnouncer", msg)
            } catch (ex: Throwable) {
                println("[SystemAnnouncer] $msg")
            }
        }
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
                    if (activeJob == coroutineContext[Job]) {
                        sco.release()
                    }
                }
            }
            activeJob = myJob
            oldJob?.cancelAndJoin()
            myJob.start()
        }
    }
}
