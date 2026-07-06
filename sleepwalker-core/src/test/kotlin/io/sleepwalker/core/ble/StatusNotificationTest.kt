package io.sleepwalker.core.ble

import io.sleepwalker.core.protocol.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusNotificationTest {

    @Test fun parse_received() {
        // seq=1, status=RECEIVED, ctx_len=0
        val data = byteArrayOf(0x01, 0x00, Status.RECEIVED.toByte(), 0x00)
        val n = StatusNotification.parse(data)
        assertNotNull(n)
        assertEquals(1, n!!.seqId)
        assertEquals(Status.RECEIVED, n.status)
        assertEquals(0, n.context.size)
        assertEquals("received", n.statusName)
    }

    @Test fun parse_tooShort() {
        assertNull(StatusNotification.parse(byteArrayOf(0x01, 0x00)))
    }

    @Test fun parse_withContext() {
        // seq=2, status=BAD_CRC, ctx_len=2, ctx={0xaa,0xbb}
        val data = byteArrayOf(0x02, 0x00, Status.BAD_CRC.toByte(), 0x02, 0xaa.toByte(), 0xbb.toByte())
        val n = StatusNotification.parse(data)
        assertNotNull(n)
        assertEquals(2, n!!.seqId)
        assertEquals(Status.BAD_CRC, n.status)
        assertEquals(2, n.context.size)
    }

    @Test fun chunker_singleChunk() {
        val frame = ByteArray(10)
        val chunks = BleWriter.chunkFrame(frame, mtu = 23)
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
    }

    @Test fun chunker_multiChunk() {
        val frame = ByteArray(60)
        val chunks = BleWriter.chunkFrame(frame, mtu = 23)
        assertTrue(chunks.size > 1)
        // Each chunk except the last must be <= maxWriteSize.
        val max = BleWriter.maxWriteSize(23)
        for (i in 0 until chunks.size - 1) {
            assertTrue(chunks[i].size <= max)
        }
    }
}