package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.EchoController
import dev.nilp0inter.subspace.audio.SttController
import dev.nilp0inter.subspace.audio.TtsController
import dev.nilp0inter.subspace.audio.SttTtsController
import dev.nilp0inter.subspace.channel.JournalPttController
import dev.nilp0inter.subspace.channel.KeyboardPttController

/** Holds typed references to all PTT channel controllers. */
internal class PttControllerRegistry(
    var echo: EchoController,
    var sttController: SttController? = null,
    var ttsController: TtsController? = null,
    var sttTtsController: SttTtsController? = null,
    var journalPttController: JournalPttController? = null,
    var keyboardController: KeyboardPttController? = null,
    var sttModelDir: java.io.File? = null,
    var supertonicModelDir: java.io.File? = null,
)
