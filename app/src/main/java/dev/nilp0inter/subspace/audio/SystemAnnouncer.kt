package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.TtsModelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    suspend fun precompute(
        vocabulary: Map<String, String>,
        voiceStylePath: String,
        scoRate: Int
    ) {
        while (synthesizer.modelStatus != TtsModelStatus.Ready) {
            if (synthesizer.modelStatus == TtsModelStatus.Failed) {
                return
            }
            delay(100)
        }

        withContext(Dispatchers.Default) {
            for ((key, text) in vocabulary) {
                val request = SynthesisRequest(
                    text = text,
                    voiceStylePath = voiceStylePath,
                    lang = "en",
                    totalSteps = 20,
                    speed = 1.2f
                )
                val outcome = synthesizer.synthesize(request)
                if (outcome is SynthesisOutcome.Success) {
                    val pcm = TtsAudio.toScoPlayback(outcome.samples, scoRate)
                    cache[key] = pcm
                }
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
