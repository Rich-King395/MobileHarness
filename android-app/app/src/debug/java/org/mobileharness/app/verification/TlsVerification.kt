package org.mobileharness.app.verification

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.RunState
import org.mobileharness.app.model.OkHttpModelTransport
import org.mobileharness.app.model.OneShotJsonBody
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.Proxy
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import java.security.cert.PKIXReason
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One unauthenticated empty request; provider URLs, certificates and exception text stay in RAM. */
class TlsVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING",
        val httpStatus: Int? = null, val networkRequests: Int = 0,
        val causeDepth: Int = 0, val flags: Map<String, Boolean> = emptyMap(),
    )
    private val call = AtomicReference<Call?>(null)
    fun cancel() { call.getAndSet(null)?.cancel() }

    suspend fun run(report: (Result) -> Unit, noHttpProxy: Boolean = false) {
        if (!RUNNING.compareAndSet(false, true)) {
            report(Result(status = "BLOCKED", code = "DIAGNOSTIC_ALREADY_RUNNING")); return
        }
        val flags = linkedMapOf(
            "api_key_read" to false, "authorization_header_present" to false,
            "screenshots_taken" to false, "user_task_read" to false,
            "runtime_controls_modified" to false, "certificate_validation_overridden" to false,
            "hostname_validation_overridden" to false,
            "diagnostic_http_proxy_bypassed" to noHttpProxy,
            "system_network_settings_modified" to false,
        )
        var attempted = 0
        val body = "{}".toByteArray(Charsets.UTF_8)
        try {
            val app = context as? HarnessApplication ?: throw Blocked("INVALID_STATE")
            withContext(Dispatchers.Main.immediate) { requireIdle(app) }
            flags.putAll(networkFlags())
            val endpoint = withContext(Dispatchers.Default) { QwenGateway.endpoint(app.profiles.metadata("gui").baseUrl) }
            // A copied production client changes only bounded timeouts. TLS trust, proxy,
            // hostname validation, replay blocking, redirects and retries remain unchanged.
            val builder = OkHttpModelTransport().client.newBuilder()
                .callTimeout(15, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            // Explicit no-key A/B probe only; the production client and system VPN stay unchanged.
            if (noHttpProxy) builder.proxy(Proxy.NO_PROXY)
            val client = builder.build()
            flags["production_client_reused"] = true
            flags["automatic_http_retries_disabled"] = !client.retryOnConnectionFailure
            flags["redirects_disabled"] = !client.followRedirects && !client.followSslRedirects
            val request = Request.Builder().url(endpoint).post(OneShotJsonBody(body)).build()
            flags["authorization_header_present"] = request.header("Authorization") != null
            flags["one_shot_body"] = request.body?.isOneShot() == true
            withContext(Dispatchers.Main.immediate) { requireIdle(app) }
            attempted = 1
            report(Result(networkRequests = attempted, flags = flags.toMap()))
            val status = executeOnce(client.newCall(request))
            flags["https_response_received"] = true
            report(Result(status = "PASS", code = "HTTPS_RESPONSE_RECEIVED", httpStatus = status,
                networkRequests = attempted, flags = flags.toMap()))
        } catch (cancelled: CancellationException) {
            report(Result(status = "CANCELLED", code = "CANCELLED", networkRequests = attempted, flags = flags.toMap()))
            throw cancelled
        } catch (blocked: Blocked) {
            report(Result(status = "BLOCKED", code = blocked.code, networkRequests = attempted, flags = flags.toMap()))
        } catch (failure: IOException) {
            val proof = classify(failure)
            flags.putAll(proof.flags)
            report(Result(status = "DIAGNOSED", code = proof.code, networkRequests = attempted,
                causeDepth = proof.depth, flags = flags.toMap()))
        } catch (_: Exception) {
            report(Result(status = "BLOCKED", code = "CONFIGURATION_OR_DIAGNOSTIC_UNAVAILABLE",
                networkRequests = attempted, flags = flags.toMap()))
        } finally {
            cancel()
            body.fill(0)
            RUNNING.set(false)
        }
    }

    private suspend fun executeOnce(value: Call): Int = suspendCancellableCoroutine { continuation ->
        call.set(value)
        continuation.invokeOnCancellation { value.cancel() }
        if (!continuation.isActive) { value.cancel(); return@suspendCancellableCoroutine }
        value.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                this@TlsVerification.call.compareAndSet(call, null)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                // A 401/4xx response is useful HTTPS evidence. Never inspect its body.
                val status = response.use { it.code }
                this@TlsVerification.call.compareAndSet(call, null)
                if (continuation.isActive) continuation.resume(status)
            }
        })
    }

    private fun requireIdle(app: HarnessApplication) {
        val service = HarnessAccessibilityService.instance?.takeIf { it.connected }
            ?: throw Blocked("SERVICE_DISCONNECTED")
        if (service.controls !== app.runtime || app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.inputInFlight) {
            throw Blocked("ACTIVE_TASK_OR_INPUT")
        }
    }

    private fun networkFlags(): Map<String, Boolean> {
        val result = linkedMapOf("network_state_available" to false, "network_present" to false,
            "internet" to false, "validated" to false, "wifi" to false, "cellular" to false,
            "vpn" to false, "default_proxy_present" to false)
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            result["network_state_available"] = true
            result["network_present"] = network != null
            result["internet"] = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            result["validated"] = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            result["wifi"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            result["cellular"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            result["vpn"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            result["default_proxy_present"] = manager.defaultProxy != null
        } catch (_: Exception) { /* Fixed absence flags, never platform messages. */ }
        return result
    }

    private data class Proof(val code: String, val depth: Int, val flags: Map<String, Boolean>)
    private fun classify(error: IOException): Proof {
        val chain = ArrayList<Throwable>()
        val visited = IdentityHashMap<Throwable, Boolean>()
        var cursor: Throwable? = error
        while (cursor != null && chain.size < 8 && visited.put(cursor, true) == null) {
            chain.add(cursor)
            cursor = cursor.cause
        }
        fun any(test: (Throwable) -> Boolean) = chain.any(test)
        fun messageContains(vararg fragments: String) = any { item ->
            val text = item.message?.lowercase(Locale.ROOT).orEmpty()
            fragments.any { text.contains(it) }
        }
        fun reason(value: CertPathValidatorException.Reason) = any { it is CertPathValidatorException && it.reason == value }
        val flags = linkedMapOf(
            "cause_ssl_handshake" to any { it is SSLHandshakeException },
            "cause_ssl_peer_unverified" to any { it is SSLPeerUnverifiedException },
            "cause_ssl_protocol" to any { it is SSLProtocolException },
            "cause_certificate" to any { it is CertificateException },
            "cause_certificate_expired" to any { it is CertificateExpiredException },
            "cause_certificate_not_yet_valid" to any { it is CertificateNotYetValidException },
            "cause_cert_path_validator" to any { it is CertPathValidatorException },
            "cert_reason_no_trust_anchor" to reason(PKIXReason.NO_TRUST_ANCHOR),
            "cert_reason_expired" to reason(CertPathValidatorException.BasicReason.EXPIRED),
            "cert_reason_not_yet_valid" to reason(CertPathValidatorException.BasicReason.NOT_YET_VALID),
            "cert_reason_algorithm_constrained" to reason(CertPathValidatorException.BasicReason.ALGORITHM_CONSTRAINED),
            "cert_reason_invalid_signature" to reason(CertPathValidatorException.BasicReason.INVALID_SIGNATURE),
            "cause_eof" to any { it is EOFException },
            "cause_socket_timeout" to any { it is SocketTimeoutException },
            "cause_unknown_host" to any { it is UnknownHostException },
            "cause_connect" to any { it is ConnectException },
            "cause_socket" to any { it is SocketException },
            "message_trust_anchor" to messageContains("trust anchor", "trustanchor"),
            "message_connection_reset" to messageContains("connection reset", "econnreset"),
            "message_connection_closed" to messageContains("connection closed", "peer closed", "socket closed", "connection was closed"),
            "message_expired" to messageContains("expired", "notafter"),
            "message_not_yet_valid" to messageContains("not yet valid", "notyetvalid", "notbefore"),
            "message_handshake_terminated" to messageContains("handshake terminated", "handshake aborted"),
            "message_hostname_not_verified" to messageContains("hostname not verified", "hostname was not verified", "no subject alternative"),
            "cause_chain_truncated_or_cyclic" to (cursor != null),
            "unknown_cause_type" to any { it !is IOException && it !is CertificateException && it !is CertPathValidatorException },
        )
        val code = when {
            flags["cause_certificate_expired"] == true || flags["cert_reason_expired"] == true -> "CERTIFICATE_EXPIRED"
            flags["cause_certificate_not_yet_valid"] == true || flags["cert_reason_not_yet_valid"] == true -> "CERTIFICATE_NOT_YET_VALID"
            flags["cert_reason_no_trust_anchor"] == true -> "CERT_PATH_NO_TRUST_ANCHOR"
            flags["cert_reason_algorithm_constrained"] == true -> "CERT_ALGORITHM_CONSTRAINED"
            flags["cert_reason_invalid_signature"] == true -> "CERT_INVALID_SIGNATURE"
            flags["cause_ssl_peer_unverified"] == true -> "TLS_PEER_UNVERIFIED"
            flags["cause_ssl_handshake"] == true -> "TLS_HANDSHAKE_FAILED"
            flags["cause_ssl_protocol"] == true -> "TLS_PROTOCOL_FAILED"
            flags["cause_socket_timeout"] == true -> "TIMEOUT"
            flags["cause_unknown_host"] == true -> "DNS_FAILURE"
            flags["cause_connect"] == true -> "CONNECT_FAILED"
            flags["cause_eof"] == true -> "EOF_DURING_REQUEST"
            flags["cause_socket"] == true -> "SOCKET_FAILURE"
            else -> "NETWORK_IO"
        }
        return Proof(code, chain.size, flags)
    }
    private class Blocked(val code: String) : Exception(code)
    private companion object { val RUNNING = AtomicBoolean(false) }
}
