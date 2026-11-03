package org.mobileharness.app.model

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class TransportReasonTest {
    @Test fun classifiesOnlyFixedTypeLabelsWithoutCopyingExceptionText() {
        val privateMessage = "SYNTHETIC_PRIVATE_URL_KEY_AND_ERROR_BODY"
        val cases = listOf(
            UnknownHostException(privateMessage) to "DNS_FAILURE",
            SSLHandshakeException(privateMessage) to "TLS_HANDSHAKE_FAILED",
            SSLPeerUnverifiedException(privateMessage) to "TLS_PEER_UNVERIFIED",
            SocketTimeoutException(privateMessage) to "TIMEOUT",
            ConnectException(privateMessage) to "CONNECT_FAILED",
            RequestReplayBlockedException() to "REQUEST_REPLAY_BLOCKED",
            IOException(privateMessage) to "NETWORK_IO",
        )
        val whitelist = setOf("DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT", "CONNECT_FAILED", "REQUEST_REPLAY_BLOCKED", "NETWORK_IO")
        cases.forEach { (error, expected) ->
            val failure = transportFailure(error)
            assertEquals("MODEL", failure.category)
            assertEquals("TRANSPORT_ERROR", failure.code)
            assertEquals("TRANSPORT_ERROR", failure.message)
            assertEquals(expected, failure.transportReason)
            assertTrue(failure.transportReason in whitelist)
            assertNull(failure.httpStatus)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(privateMessage))
        }
    }

    @Test fun classificationDoesNotTraverseCauseOrReadOverriddenMessage() {
        val error = object : IOException() {
            override val message: String get() = throw AssertionError("Raw exception text must not be read")
        }
        error.initCause(UnknownHostException("SYNTHETIC_PRIVATE_CAUSE"))
        val failure = transportFailure(error)
        assertEquals("NETWORK_IO", failure.transportReason)
        assertNull(failure.cause)
    }

    @Test fun secondBodyWriteProducesOnlyTheSafeReplayLabel() {
        val body = OneShotJsonBody("{}".toByteArray())
        body.writeTo(okio.Buffer())
        try { body.writeTo(okio.Buffer()); fail("Repeated request body was allowed") }
        catch (error: IOException) {
            assertEquals("REQUEST_REPLAY_BLOCKED", transportFailure(error).transportReason)
        }
    }
}
