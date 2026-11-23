package com.example

import com.example.aare.protocol.AapProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AapProtocolTest {

    @Test
    fun `version payload roundtrip`() {
        val payload = AapProtocol.buildVersionPayload(1, 6)
        val parsed = AapProtocol.parseVersionPayload(payload)
        assertNotNull(parsed)
        assertEquals(1 to 6, parsed)
    }

    @Test
    fun `writeFrame and readFrame roundtrip`() {
        val out = ByteArrayOutputStream()
        val body = AapProtocol.buildVersionPayload(1, 6)
        AapProtocol.writeFrame(
            output = out,
            channelId = AapProtocol.CH_CONTROL,
            messageType = AapProtocol.MSG_VERSION_RESPONSE,
            body = body,
            control = false,
            sslEngine = null
        )

        val frame = AapProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(frame)
        assertEquals(AapProtocol.CH_CONTROL, frame!!.channelId)
        assertEquals(AapProtocol.MSG_VERSION_RESPONSE, frame.messageType())
        assertEquals(1 to 6, AapProtocol.parseVersionPayload(frame.messageBody()))
    }

    @Test
    fun `auth complete status codes decode properly`() {
        val okBytes = AapProtocol.encodeSignedInt32Field(1, AapProtocol.AUTH_STATUS_OK)
        val rejectedBytes = AapProtocol.encodeSignedInt32Field(1, AapProtocol.AUTH_STATUS_CERT_REJECTED)
        val expiredBytes = AapProtocol.encodeSignedInt32Field(1, AapProtocol.AUTH_STATUS_CERT_EXPIRED)

        assertEquals(0, AapProtocol.decodeFirstInt32Field(okBytes, 1))
        assertEquals(-3, AapProtocol.decodeFirstInt32Field(rejectedBytes, 1))
        assertEquals(-24, AapProtocol.decodeFirstInt32Field(expiredBytes, 1))
        assertTrue(AapProtocol.describeAuthStatus(-3).contains("-3"))
    }
}
