package io.sleepwalker.core.hid

import io.sleepwalker.core.protocol.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.7 / 2.8: status notification parsing at the session boundary.
 */
class SessionStatusParserTest {

    @Test fun parse_received_notification() {
        // seq=1, status=RECEIVED, ctx_len=0
        val data = byteArrayOf(0x01, 0x00, Status.RECEIVED.toByte(), 0x00)
        val s = SessionStatusParser.parse(data)
        assertNotNull(s)
        assertEquals(1, s!!.seqId)
        assertEquals(Status.RECEIVED, s.status)
        assertEquals("received", s.statusName)
        assertEquals(0, s.context.size)
    }

    @Test fun parse_preserves_context_bytes() {
        // seq=2, status=BAD_CRC, ctx_len=2, ctx={0xaa,0xbb}
        val data = byteArrayOf(
            0x02, 0x00, Status.BAD_CRC.toByte(), 0x02,
            0xaa.toByte(), 0xbb.toByte())
        val s = SessionStatusParser.parse(data)
        assertNotNull(s)
        assertEquals(2, s!!.seqId)
        assertEquals(Status.BAD_CRC, s.status)
        assertEquals(2, s.context.size)
        assertEquals(0xaa.toByte(), s.context[0])
        assertEquals(0xbb.toByte(), s.context[1])
    }

    @Test fun parse_malformed_returns_null() {
        assertNull(SessionStatusParser.parse(byteArrayOf(0x01, 0x00)))
    }

    @Test fun is_known_status_and_name() {
        assertTrue(SessionStatusParser.isKnownStatus(Status.RECEIVED))
        assertFalse(SessionStatusParser.isKnownStatus(0xFF))
        assertEquals("received", SessionStatusParser.nameFor(Status.RECEIVED))
        assertEquals("unknown", SessionStatusParser.nameFor(0xFF))
    }
}
