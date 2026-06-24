package dev.nilp0inter.subspace.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OggEncoderInstrumentedTest {
    @Test
    fun syntheticPcmProducesNonEmptyOggFile() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val output = File(context.cacheDir, "synthetic-${System.nanoTime()}.ogg")
            val pcm = ShortArray(SAMPLE_RATE) { index ->
                (sin(2.0 * PI * 440.0 * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.25).toInt().toShort()
            }

            val result = OggEncoder().encode(pcm, output)

            assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
            assertTrue(output.isFile)
            assertTrue(output.length() > 0L)
            output.inputStream().use { input ->
                val header = ByteArray(4)
                assertTrue(input.read(header) == header.size)
                assertArrayEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()), header)
            }
            output.delete()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
